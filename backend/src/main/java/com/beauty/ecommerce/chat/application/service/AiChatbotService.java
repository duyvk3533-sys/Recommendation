package com.beauty.ecommerce.chat.application.service;

import com.beauty.ecommerce.chat.domain.entity.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AiChatbotService {

    @Value("${gemini.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public String generateReply(String userMessage, List<ChatMessage> history) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Xin chào! Tôi là Trợ lý AI chăm sóc da của Glowzy. Hiện tại tôi chưa được cấu hình khóa AI, bạn vui lòng liên hệ nhân viên hỗ trợ nhé!";
        }

        if (apiKey.startsWith("gsk_")) {
            log.info("[CHAT-AI] Đang sử dụng Groq API để trả lời...");
            return callGroqApi(userMessage, history);
        } else {
            log.info("[CHAT-AI] Đang sử dụng Google Gemini API để trả lời...");
            return callGeminiApi(userMessage, history);
        }
    }

    private String callGroqApi(String userMessage, List<ChatMessage> history) {
        try {
            String url = "https://api.groq.com/openai/v1/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            List<Map<String, String>> messages = new ArrayList<>();
            
            // System instruction
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Bạn là Trợ lý AI chăm sóc da thông minh và thân thiện của cửa hàng mỹ phẩm Glowzy. " +
                    "Hãy tư vấn cho khách hàng về loại da, các bước skincare, cách sử dụng mỹ phẩm, và giới thiệu các dòng sản phẩm của Glowzy. " +
                    "Hãy trả lời bằng tiếng Việt lịch sự, ngắn gọn, súc tích và hữu ích để phù hợp với giao diện chatbox.");
            messages.add(systemMsg);

            // History context (limit to last 10 messages)
            int startIdx = Math.max(0, history.size() - 10);
            for (int i = startIdx; i < history.size(); i++) {
                ChatMessage h = history.get(i);
                Map<String, String> msg = new HashMap<>();
                msg.put("role", "AI".equals(h.getSenderId()) ? "assistant" : "user");
                msg.put("content", h.getContent());
                messages.add(msg);
            }

            // Current message
            Map<String, String> currentMsg = new HashMap<>();
            currentMsg.put("role", "user");
            currentMsg.put("content", userMessage);
            messages.add(currentMsg);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.1-8b-instant");
            requestBody.put("messages", messages);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                List choices = (List) body.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map choice = (Map) choices.get(0);
                    Map message = (Map) choice.get("message");
                    if (message != null) {
                        return (String) message.get("content");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error calling Groq API for chatbot: {}", e.getMessage());
        }
        return "Xin lỗi bạn, kết nối của tôi đang gặp sự cố nhỏ, hãy nhắn lại sau nhé!";
    }

    private String callGeminiApi(String userMessage, List<ChatMessage> history) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            List<Map<String, Object>> contents = new ArrayList<>();

            // History context (limit to last 10 messages)
            int startIdx = Math.max(0, history.size() - 10);
            for (int i = startIdx; i < history.size(); i++) {
                ChatMessage h = history.get(i);
                Map<String, Object> contentMap = new HashMap<>();
                contentMap.put("role", "AI".equals(h.getSenderId()) ? "model" : "user");
                
                Map<String, String> partText = new HashMap<>();
                partText.put("text", h.getContent());
                contentMap.put("parts", List.of(partText));
                
                contents.add(contentMap);
            }

            // Current message
            Map<String, Object> currentContent = new HashMap<>();
            currentContent.put("role", "user");
            Map<String, String> partText = new HashMap<>();
            partText.put("text", userMessage);
            currentContent.put("parts", List.of(partText));
            contents.add(currentContent);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", contents);

            // System instruction
            Map<String, Object> systemInstruction = new HashMap<>();
            Map<String, String> systemPart = new HashMap<>();
            systemPart.put("text", "Bạn là Trợ lý AI chăm sóc da thông minh và thân thiện của cửa hàng mỹ phẩm Glowzy. " +
                    "Hãy tư vấn cho khách hàng về loại da, các bước skincare, cách sử dụng mỹ phẩm, và giới thiệu các dòng sản phẩm của Glowzy. " +
                    "Hãy trả lời bằng tiếng Việt lịch sự, ngắn gọn, súc tích và hữu ích để phù hợp với giao diện chatbox.");
            systemInstruction.put("parts", List.of(systemPart));
            requestBody.put("systemInstruction", systemInstruction);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                List candidates = (List) body.get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map candidate = (Map) candidates.get(0);
                    Map content = (Map) candidate.get("content");
                    if (content != null) {
                        List parts = (List) content.get("parts");
                        if (parts != null && !parts.isEmpty()) {
                            Map part = (Map) parts.get(0);
                            return (String) part.get("text");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error calling Gemini API for chatbot: {}", e.getMessage());
        }
        return "Xin lỗi bạn, kết nối của tôi đang gặp sự cố nhỏ, hãy nhắn lại sau nhé!";
    }
}
