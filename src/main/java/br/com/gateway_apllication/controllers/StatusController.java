package br.com.gateway_apllication.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    @GetMapping("/status-endpoint")
    public String status() {
        return "Gateway OK";
    }
}
