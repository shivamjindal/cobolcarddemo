package com.carddemo.online;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.WebUtils;

import com.carddemo.identity.SignedOnUser;

/**
 * The CICS region for one request. One request is one task: one DB transaction, the XCTL chain run
 * inside it with an access check on every screen, identity changes applied after commit, and the
 * conversation stored last. Requests of one session run one at a time.
 */
@Component
final class Dispatcher {

    static final int MAX_TRANSFERS = 16;

    private final ProgramCatalog catalog;
    private final TerminalIdentity identity;
    private final TransactionTemplate tasks;
    private final Header header;

    Dispatcher(ProgramCatalog catalog, TerminalIdentity identity, TransactionTemplate tasks, Header header) {
        this.catalog = catalog;
        this.identity = identity;
        this.tasks = tasks;
        this.header = header;
    }

    sealed interface Result {
        record Ok(Reply reply) implements Result {}

        record Stale(Reply current) implements Result {}

        record NoConversation() implements Result {}

        record UnknownTransaction(String transid) implements Result {}

        record UnknownAction(String action) implements Result {}

        record BadRequest(String message) implements Result {}

        record Forbidden() implements Result {}
    }

    Result current(HttpServletRequest request) {
        return Optional.ofNullable(request.getSession(false))
                .flatMap(Conversation::in)
                .<Result>map(c -> new Result.Ok(c.lastReply()))
                .orElseGet(Result.NoConversation::new);
    }

    /** A transaction id typed on a clear screen. Whatever identity and COMMAREA there were are gone. */
    Result start(String transid, HttpServletRequest request, HttpServletResponse response) {
        Optional<Screen<?, ?, ?, ?>> found = transid != null && transid.matches("[A-Z0-9@#$]{1,4}")
                ? catalog.byTransid(TransId.of(transid))
                : Optional.empty();
        if (found.isEmpty()) {
            return new Result.UnknownTransaction(transid);
        }
        Screen<?, ?, ?, ?> screen = found.get();
        if (!screen.access().permits(Optional.empty())) {
            return new Result.Forbidden();
        }
        identity.signOff(request, response);
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            return complete(() -> screen.enterFrom(Optional.empty(), Key.ENTER, Optional.empty(), Optional.empty()),
                    screen, Optional.empty(), Key.ENTER, new Terminal(), 0, request, response);
        }
    }

    /** The user pressed a key on the screen the conversation shows. */
    Result act(String route, String action, ActionRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return new Result.NoConversation();
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            Optional<Conversation> found = Conversation.in(session);
            if (found.isEmpty()) {
                return new Result.NoConversation();
            }
            Conversation conversation = found.get();
            Screen<?, ?, ?, ?> screen = catalog.byProgram(conversation.current()).orElseThrow();
            if (body == null || body.turn() == null) {
                return new Result.BadRequest("turn is required");
            }
            if (body.turn() != conversation.turn() || !screen.ref().route().equals(route)) {
                return new Result.Stale(conversation.lastReply());
            }
            Optional<SignedOnUser> user = identity.current();
            if (!screen.access().permits(user)) {
                return new Result.Forbidden();
            }
            Key aid;
            if (ScreenAction.INVALID_KEY.equals(action)) {
                if (body.key() == null) {
                    return new Result.BadRequest("invalid-key needs the key that was pressed");
                }
                if (screen.binds(body.key())) {
                    return new Result.BadRequest(body.key() + " is bound to an action of " + route);
                }
                aid = body.key();
            } else {
                Optional<Key> bound = screen.actionNamed(action).map(a -> a.key());
                if (bound.isEmpty()) {
                    return new Result.UnknownAction(action);
                }
                aid = bound.get();
            }
            Terminal terminal = conversation.terminal().copy();
            Map<String, String> typed = body.fields() == null ? Map.of() : body.fields();
            return complete(() -> screen.press(terminal, aid, typed, conversation.state()),
                    screen, user, aid, terminal, conversation.turn(), request, response);
        }
    }

    private record Task(Screen<?, ?, ?, ?> screen, Outcome last, IdentityChange identity) {}

    private Result complete(Supplier<Outcome> first, Screen<?, ?, ?, ?> screen, Optional<SignedOnUser> user, Key aid,
            Terminal terminal, long turn, HttpServletRequest request, HttpServletResponse response) {
        Task task;
        try {
            task = tasks.execute(tx -> {
                Task ran = run(first, screen, user, aid);
                if (ran.last() instanceof Abend) {
                    tx.setRollbackOnly();
                }
                return ran;
            });
        } catch (Terminal.BadInput e) {
            return new Result.BadRequest(e.getMessage());
        } catch (AccessDenied e) {
            return new Result.Forbidden();
        }
        switch (task.identity()) {
            case IdentityChange.Keep keep -> {
            }
            case IdentityChange.SignIn signIn -> identity.signIn(signIn.user(), request, response);
            case IdentityChange.SignOff signOff -> identity.signOff(request, response);
        }
        long next = turn + 1;
        Screen<?, ?, ?, ?> at = task.screen();
        switch (task.last()) {
            case Show show -> {
                header.fill(show.map(), at.ref());
                terminal.sendMap(show.map(), show.mode());
                return store(new Conversation(next, at.ref().program(), show.state(), terminal,
                        screenReply(next, at, terminal)), request);
            }
            case ShowText text -> {
                terminal.sendText(text.text());
                return store(new Conversation(next, at.ref().program(), text.state(), terminal,
                        new TextReply(next, at.ref().route(), terminal.text().orElseThrow(), actions(at))), request);
            }
            case End end -> {
                terminal.sendText(end.text());
                identity.signOff(request, response);
                return new Result.Ok(new EndedReply(next, terminal.text().orElseThrow()));
            }
            case Abend abend -> {
                terminal.sendText(header.abendMessage(screen.ref().transid().orElseThrow(), abend.code()));
                identity.signOff(request, response);
                return new Result.Ok(new EndedReply(next, terminal.text().orElseThrow()));
            }
            case Transfer transfer -> throw new IllegalStateException("task ended on an XCTL");
        }
    }

    private Task run(Supplier<Outcome> first, Screen<?, ?, ?, ?> screen, Optional<SignedOnUser> user, Key aid) {
        Outcome out = first.get();
        Screen<?, ?, ?, ?> at = screen;
        IdentityChange change = new IdentityChange.Keep();
        Optional<SignedOnUser> who = user;
        int hops = 0;
        while (out instanceof Transfer transfer) {
            if (++hops > MAX_TRANSFERS) {
                throw new IllegalStateException("more than " + MAX_TRANSFERS + " XCTLs from " + screen.ref().program());
            }
            if (transfer.ifNotInstalled().isPresent() && !catalog.installed(transfer.to())) {
                out = transfer.ifNotInstalled().get();
                continue;
            }
            if (!(transfer.identity() instanceof IdentityChange.Keep)) {
                change = transfer.identity();
            }
            who = transfer.identity().applyTo(who);
            ProgramCatalog.Resolved target = catalog.resolve(transfer.to(), transfer.handoff());
            if (!target.screen().access().permits(who)) {
                throw new AccessDenied(transfer.to());
            }
            out = target.screen().enterFrom(Optional.of(at.ref().program()), aid, target.handoff(), who);
            at = target.screen();
        }
        return new Task(at, out, change);
    }

    private static Result store(Conversation conversation, HttpServletRequest request) {
        conversation.storeIn(request.getSession());
        return new Result.Ok(conversation.lastReply());
    }

    private static ScreenReply screenReply(long turn, Screen<?, ?, ?, ?> screen, Terminal terminal) {
        MapId<?> map = terminal.map().orElseThrow();
        List<String> hidden = map.fields().stream()
                .filter(f -> f.bms().has(Attribute.DRK))
                .map(Enum::name)
                .toList();
        return new ScreenReply(turn, screen.ref().route(), new MapRef(map.mapset(), map.name()), terminal.shownValues(),
                terminal.colors(), terminal.protectedFields(), hidden, terminal.cursor(), actions(screen));
    }

    private static List<ActionRef> actions(Screen<?, ?, ?, ?> screen) {
        return screen.actionList().stream().map(a -> new ActionRef(a.route(), a.key())).toList();
    }

    static final class AccessDenied extends RuntimeException {
        AccessDenied(ProgramName program) {
            super(program.value());
        }
    }
}
