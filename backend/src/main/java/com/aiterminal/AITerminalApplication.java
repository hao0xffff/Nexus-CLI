package com.aiterminal;

import com.aiterminal.util.OutputPrinter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AITerminalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AITerminalApplication.class, args);
        OutputPrinter.printProjectLogo();
    }
}
