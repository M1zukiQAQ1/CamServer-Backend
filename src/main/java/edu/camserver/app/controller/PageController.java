package edu.camserver.app.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    private final String seeingMonitorUrl;

    public PageController(@Value("${app.site.public-url:}") String publicUrl) {
        String base = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
        this.seeingMonitorUrl = base + "/seeing-monitor";
    }

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
        return "gallery";
    }

    /** The seeing monitor lives on the Nuxt site now; the old MJPEG page is gone. */
    @GetMapping({"/seeingMonitor", "/live", "/live_login"})
    public String seeingMonitor() {
        return "redirect:" + seeingMonitorUrl;
    }

    @GetMapping("/starTracker")
    public String starTracker() {
        return "login";
    }

}
