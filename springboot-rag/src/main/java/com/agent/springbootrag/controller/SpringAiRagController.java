package com.agent.springbootrag.controller;

import com.agent.springbootrag.rag.service.SpringAiRagEntryService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring AI 版 RAG 总入口，与 /rag/ask 并列，便于对照。
 */
@RestController
@RequestMapping("/rag/v2")
@CrossOrigin(origins = "http://localhost:5173")
public class SpringAiRagController {

    private final SpringAiRagEntryService ragEntryService;

    public SpringAiRagController(SpringAiRagEntryService ragEntryService) {
        this.ragEntryService = ragEntryService;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String question,
                      @RequestParam(required = false) String conversationId) {
        return ragEntryService.ask(question, conversationId);
    }
}
