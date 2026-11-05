package com.carddemo.operator;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Profile {@code operator}: a non-web command. The web server is not started. */
@Component
@Profile("operator")
public class OperatorRunner implements ApplicationRunner {

    private final Operator operator;

    public OperatorRunner(Operator operator) {
        this.operator = operator;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.exit(operator.execute(args.getNonOptionArgs()));
    }
}
