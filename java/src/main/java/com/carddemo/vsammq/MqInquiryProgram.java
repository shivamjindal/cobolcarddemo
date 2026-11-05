package com.carddemo.vsammq;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carddemo.LegacyDefect;
import com.carddemo.cics.Syncpoint;
import com.carddemo.mq.GetOptions;
import com.carddemo.mq.Got;
import com.carddemo.mq.Message;
import com.carddemo.mq.MessageQueues;
import com.carddemo.mq.Opened;
import com.carddemo.mq.QueueHandle;
import com.carddemo.mq.Reason;
import com.carddemo.mq.Trigger;
import com.carddemo.mq.TriggeredProgram;

/**
 * The request and reply loop COACCT01 and CODATE01 share: get each request on the triggering queue under
 * syncpoint, put a 1000-byte reply with the request's msgId and correlId on the program's reply queue, and
 * SYNCPOINT before the next get. A failure puts MQ-ERR-DISPLAY on CARD.DEMO.ERROR and ends the task normally,
 * so the error message and the replies before it commit.
 */
abstract class MqInquiryProgram implements TriggeredProgram {

    static final String ERROR_QUEUE = "CARD.DEMO.ERROR";
    static final int BUFFER = 1000;
    private static final long WAIT_MILLIS = 5000;
    private static final Logger log = LoggerFactory.getLogger(MqInquiryProgram.class);

    /** What 4000-PROCESS-REQUEST-REPLY made of one request. */
    sealed interface Answer {

        /** The REPLY-MESSAGE text, padded with spaces to 1000 bytes when it is put. */
        record Reply(byte[] text) implements Answer {
            Reply(String text) {
                this(text.getBytes(ISO_8859_1));
            }
        }

        /** 9000-ERROR with the codes and the MQ-APPL-RETURN-MESSAGE the program moves. */
        record Failure(int condition, int reason, String message) implements Answer {}
    }

    private final MessageQueues queues;
    private final Syncpoint syncpoint;

    MqInquiryProgram(MessageQueues queues, Syncpoint syncpoint) {
        this.queues = queues;
        this.syncpoint = syncpoint;
    }

    /** REPLY-QUEUE-NAME. */
    abstract String replyQueue();

    /** 4000-PROCESS-REQUEST-REPLY on REQUEST-MESSAGE, the 1000-byte MQ-BUFFER as the get left it. */
    abstract Answer answer(byte[] request);

    @Override
    public final void run(Trigger trigger) {
        new Task(trigger.queue()).run();
    }

    /** 8000-TERMINATION ends the task with EXEC CICS RETURN wherever it is performed. */
    private static final class Terminated extends RuntimeException {
        Terminated() {
            super(null, null, false, false);
        }
    }

    private final class Task {

        private final String inputQueue;
        private final byte[] mqBuffer = new byte[BUFFER];
        private Optional<QueueHandle> errorHandle = Optional.empty();
        private Optional<QueueHandle> inputHandle = Optional.empty();
        private Optional<QueueHandle> outputHandle = Optional.empty();
        private byte[] msgId = Message.NONE;
        private byte[] correlId = Message.NONE;
        private String replyToQ = "";
        private boolean noMoreMessages;

        Task(String inputQueue) {
            this.inputQueue = inputQueue;
        }

        void run() {
            try {
                openErrorQueue();
                inputHandle = Optional.of(open(inputQueue, "INP MQOPEN ERR"));
                outputHandle = Optional.of(open(replyQueue(), "OUT MQOPEN ERR"));
                getRequest();
                while (!noMoreMessages) {
                    syncpoint.commit();
                    getRequest();
                }
                terminate();
            } catch (Terminated end) {
                // the task has returned
            }
        }

        private void openErrorQueue() {
            Opened opened = queues.open(ERROR_QUEUE);
            if (opened.reason() != Reason.NONE) {
                log.warn("{}", errDisplay(opened.reason().completionCode(), opened.reason().code(), "ERR MQOPEN ERR",
                        ERROR_QUEUE));
                terminate();
            }
            errorHandle = opened.handle();
        }

        private QueueHandle open(String queue, String failure) {
            Opened opened = queues.open(queue);
            if (opened.reason() != Reason.NONE) {
                error(opened.reason().completionCode(), opened.reason().code(), failure, queue);
            }
            return opened.handle().orElseThrow();
        }

        private void getRequest() {
            msgId = Message.NONE;
            correlId = Message.NONE;
            Got got = queues.get(inputHandle.orElseThrow(), GetOptions.waitMillis(WAIT_MILLIS, true));
            if (got.ok()) {
                Message request = got.message().orElseThrow();
                msgId = request.msgId();
                correlId = request.correlId();
                replyToQ = request.replyToQ();
                receive(request.data());
                switch (answer(mqBuffer.clone())) {
                    case Answer.Reply reply -> putReply(reply.text());
                    case Answer.Failure failure -> error(failure.condition(), failure.reason(), failure.message(),
                            inputQueue);
                }
            } else if (got.reason() == Reason.NO_MSG_AVAILABLE) {
                noMoreMessages = true;
            } else {
                error(got.reason().completionCode(), got.reason().code(), "INP MQGET ERR:", inputQueue);
            }
        }

        @LegacyDefect(id = "COACCT01-short-request-reads-old-buffer",
                behavior = "MQGET fills only as many bytes of MQ-BUFFER as the message has, and the program moves "
                        + "all 1000 to REQUEST-MESSAGE, so a request shorter than function and account id is read "
                        + "with the tail of the previous reply, or low-values in a fresh task",
                fix = "clear MQ-BUFFER before each MQGET, or use only MQ-DATA-LENGTH bytes of it")
        private void receive(byte[] data) {
            System.arraycopy(data, 0, mqBuffer, 0, Math.min(data.length, BUFFER));
        }

        private void putReply(byte[] text) {
            fill(text);
            Reason reason = queues.put(outputHandle.orElseThrow(),
                    new Message(mqBuffer.clone(), msgId, correlId, replyToQ), true);
            if (reason != Reason.NONE) {
                error(reason.completionCode(), reason.code(), "MQPUT ERR", replyQueue());
            }
        }

        private void error(int condition, int reason, String message, String queue) {
            fill(errDisplay(condition, reason, message, queue).getBytes(ISO_8859_1));
            Reason put = queues.put(errorHandle.orElseThrow(),
                    new Message(mqBuffer.clone(), msgId, correlId, replyToQ), true);
            if (put != Reason.NONE) {
                log.warn("{}", errDisplay(put.completionCode(), put.code(), "MQPUT ERR", ERROR_QUEUE));
            }
            terminate();
        }

        private void fill(byte[] text) {
            Arrays.fill(mqBuffer, (byte) ' ');
            System.arraycopy(text, 0, mqBuffer, 0, Math.min(text.length, BUFFER));
        }

        private void terminate() {
            inputHandle.ifPresent(queues::close);
            outputHandle.ifPresent(queues::close);
            errorHandle.ifPresent(queues::close);
            throw new Terminated();
        }
    }

    /** MQ-ERR-DISPLAY: MQ-ERROR-PARA, which only a failed RETRIEVE sets, the message, the two codes and the queue. */
    static String errDisplay(int condition, int reason, String message, String queue) {
        return pad("", 25) + "  " + pad(message, 25) + "  " + "%02d".formatted(Math.abs(condition) % 100) + "  "
                + "%05d".formatted(Math.abs(reason) % 100000) + "  " + pad(queue, 48);
    }

    static String pad(String text, int length) {
        String value = text == null ? "" : text;
        return value.length() >= length ? value.substring(0, length) : value + " ".repeat(length - value.length());
    }
}
