package com.beauty.ecommerce.review.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SentimentAnalysisService {

    @Value("${gemini.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class SentimentResult {
        private final String sentiment;
        private final boolean appropriate;

        public SentimentResult(String sentiment, boolean appropriate) {
            this.sentiment = sentiment;
            this.appropriate = appropriate;
        }

        public String getSentiment() {
            return sentiment;
        }

        public boolean isAppropriate() {
            return appropriate;
        }
    }

    public SentimentResult analyzeSentimentAndModeration(String comment, int ratingStar) {
        if (comment == null || comment.isBlank()) {
            return new SentimentResult(getFallbackSentiment(ratingStar), true);
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.info("API key is not configured. Using rule-based fallback for review analysis.");
            return new SentimentResult(getFallbackSentiment(ratingStar), true);
        }

        String rawResponse = null;
        if (apiKey.startsWith("gsk_")) {
            log.info("Detected Groq API Key. Calling Groq (Llama 3) for moderation & sentiment...");
            rawResponse = callGroqApi(comment);
        } else {
            log.info("Calling Google Gemini API for moderation & sentiment...");
            rawResponse = callGeminiApi(comment);
        }

        if (rawResponse != null) {
            try {
                String cleanJson = extractJson(rawResponse);
                log.info("Extracted JSON response: {}", cleanJson);
                Map<String, Object> map = objectMapper.readValue(cleanJson, Map.class);
                
                String sentiment = (String) map.get("sentiment");
                Boolean appropriate = (Boolean) map.get("appropriate");

                if (sentiment != null && appropriate != null) {
                    String processed = sentiment.trim().toUpperCase();
                    if (processed.contains("POSITIVE")) {
                        return new SentimentResult("POSITIVE", appropriate);
                    } else if (processed.contains("NEGATIVE")) {
                        return new SentimentResult("NEGATIVE", appropriate);
                    } else if (processed.contains("NEUTRAL")) {
                        return new SentimentResult("NEUTRAL", appropriate);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse JSON response from AI API: {}. Trying text-based parsing.", e.getMessage());
                // Text-based fallback parsing
                String upperResponse = rawResponse.toUpperCase();
                boolean isAppropriate = !upperResponse.contains("\"APPROPRIATE\": FALSE") && 
                                        !upperResponse.contains("\"APPROPRIATE\":FALSE") &&
                                        !upperResponse.contains("APPROPRIATE: FALSE");
                if (upperResponse.contains("POSITIVE")) {
                    return new SentimentResult("POSITIVE", isAppropriate);
                } else if (upperResponse.contains("NEGATIVE")) {
                    return new SentimentResult("NEGATIVE", isAppropriate);
                } else if (upperResponse.contains("NEUTRAL")) {
                    return new SentimentResult("NEUTRAL", isAppropriate);
                }
            }
        }

        log.warn("AI API failed or returned invalid response. Falling back to rules.");
        return new SentimentResult(getFallbackSentiment(ratingStar), true);
    }

    private String callGeminiApi(String comment) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String prompt = String.format(
                    "You are a moderation and sentiment analyzer assistant.\n" +
                    "Analyze the following review comment and perform two tasks:\n" +
                    "1. Classify sentiment as POSITIVE, NEGATIVE, or NEUTRAL.\n" +
                    "2. Determine if the review is appropriate (NOT containing profanity, vulgarity, spam links, advertisements, or severe insults).\n\n" +
                    "You MUST respond ONLY with a raw JSON object matching this schema, without markdown formatting or other text:\n" +
                    "{\n" +
                    "  \"sentiment\": \"POSITIVE|NEGATIVE|NEUTRAL\",\n" +
                    "  \"appropriate\": true|false\n" +
                    "}\n\n" +
                    "Review: \"%s\"", comment
            );

            Map<String, Object> textMap = new HashMap<>();
            textMap.put("text", prompt);

            Map<String, Object> partsMap = new HashMap<>();
            partsMap.put("parts", List.of(textMap));

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(partsMap));

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
                            String text = (String) part.get("text");
                            if (text != null) {
                                return text;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while calling Gemini API: {}", e.getMessage());
        }
        return null;
    }

    private String callGroqApi(String comment) {
        try {
            String url = "https://api.groq.com/openai/v1/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            String prompt = String.format(
                    "You are a moderation and sentiment analyzer assistant.\n" +
                    "Analyze the following review comment and perform two tasks:\n" +
                    "1. Classify sentiment as POSITIVE, NEGATIVE, or NEUTRAL.\n" +
                    "2. Determine if the review is appropriate (NOT containing profanity, vulgarity, spam links, advertisements, or severe insults).\n\n" +
                    "You MUST respond ONLY with a raw JSON object matching this schema, without markdown formatting or other text:\n" +
                    "{\n" +
                    "  \"sentiment\": \"POSITIVE|NEGATIVE|NEUTRAL\",\n" +
                    "  \"appropriate\": true|false\n" +
                    "}\n\n" +
                    "Review: \"%s\"", comment
            );

            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "user");
            messageMap.put("content", prompt);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.1-8b-instant");
            requestBody.put("messages", List.of(messageMap));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                List choices = (List) body.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map choice = (Map) choices.get(0);
                    Map message = (Map) choice.get("message");
                    if (message != null) {
                        String text = (String) message.get("content");
                        if (text != null) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while calling Groq API: {}", e.getMessage());
        }
        return null;
    }

    private String extractJson(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private String getFallbackSentiment(int ratingStar) {
        if (ratingStar >= 4) {
            return "POSITIVE";
        } else if (ratingStar <= 2) {
            return "NEGATIVE";
        } else {
            return "NEUTRAL";
        }
    }
}
