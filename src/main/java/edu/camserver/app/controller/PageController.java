package edu.camserver.app.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/robots.txt")
    public String robots() {
        return "robots";
    }

    @GetMapping("/allSky")
    public String allSky() {
        return "allSky";
    }

    @GetMapping("/seeingMonitor")
    public String seeingMonitor() {
        return "login";
    }

    @GetMapping("/starTracker")
    public String starTracker() {
        return "login";
    }

    @GetMapping("/live")
    public String live() {
        return "login";
    }

    @GetMapping("/live_login")
    public String liveLogin() {
        return "live";
    }

}