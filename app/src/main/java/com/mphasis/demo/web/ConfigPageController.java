package com.mphasis.demo.web;

import com.mphasis.demo.service.ConfigurationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ConfigPageController {

    private final ConfigurationService configurationService;

    public ConfigPageController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @GetMapping("/")
    public String configurationPage(Model model) {
        model.addAttribute("configuration", configurationService.getConfiguration());
        model.addAttribute("source", configurationService.getSource());
        model.addAttribute("lastUpdated", configurationService.getLastUpdated());
        return "config";
    }

    @GetMapping("/api/config")
    @ResponseBody
    public Map<String, Object> configuration() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", configurationService.getSource());
        body.put("lastUpdated", configurationService.getLastUpdated().toString());
        body.put("configuration", configurationService.getConfiguration());
        return body;
    }
}
