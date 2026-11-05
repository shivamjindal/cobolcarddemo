package com.carddemo;

import java.nio.file.Path;
import java.util.List;

import com.carddemo.dli.Dbpautp0Unload;
import com.carddemo.dli.PendingAuthorizationLoader;
import com.carddemo.vsam.RoundTripCheck;
import com.carddemo.vsam.SchemaGenerator;
import com.carddemo.vsam.VsamLoader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("load")
public class LoadRunner implements ApplicationRunner {

    private final VsamLoader loader;
    private final RoundTripCheck check;
    private final PendingAuthorizationLoader pendingAuthorizations;
    private final String appRoot;

    public LoadRunner(
            VsamLoader loader,
            RoundTripCheck check,
            PendingAuthorizationLoader pendingAuthorizations,
            @Value("${carddemo.load.app-root:}") String appRoot) {
        this.loader = loader;
        this.check = check;
        this.pendingAuthorizations = pendingAuthorizations;
        this.appRoot = appRoot;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (appRoot == null || appRoot.isBlank()) {
            throw new IllegalStateException("carddemo.load.app-root is required with the load profile");
        }
        Path root = Path.of(appRoot);
        SchemaGenerator.Generated generated = SchemaGenerator.generate(root);
        loader.load(root, generated.tables());
        RoundTripCheck.Report report = check.check(root, generated.tables());
        System.out.print(report);
        if (!report.ok()) {
            throw new IllegalStateException("round-trip check failed");
        }
        Dbpautp0Unload.Contents unload = pendingAuthorizations.load(root);
        List<Integer> loaded = pendingAuthorizations.counts();
        System.out.printf("pending_auth_summaries: source %d, loaded %d%npending_auth_details: source %d, loaded %d%n",
                unload.roots().size(), loaded.get(0), unload.children().size(), loaded.get(1));
        if (loaded.get(0) != unload.roots().size() || loaded.get(1) != unload.children().size()) {
            throw new IllegalStateException("DBPAUTP0 count mismatch");
        }
    }
}
