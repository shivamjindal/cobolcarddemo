package com.carddemo;

import java.nio.file.Path;

import com.carddemo.vsam.RoundTripCheck;
import com.carddemo.vsam.SchemaGenerator;
import com.carddemo.vsam.VsamLoader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public class LoadRunner implements ApplicationRunner, ExitCodeGenerator {

    private final VsamLoader loader;
    private final RoundTripCheck check;
    private final String appRoot;
    private int exitCode;

    public LoadRunner(
            VsamLoader loader,
            RoundTripCheck check,
            @Value("${carddemo.load.app-root:}") String appRoot) {
        this.loader = loader;
        this.check = check;
        this.appRoot = appRoot;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (appRoot == null || appRoot.isBlank()) {
            return;
        }
        Path root = Path.of(appRoot);
        SchemaGenerator.Generated generated = SchemaGenerator.generate(root);
        loader.load(root, generated.tables());
        RoundTripCheck.Report report = check.check(root, generated.tables());
        System.out.print(report);
        exitCode = report.ok() ? 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
