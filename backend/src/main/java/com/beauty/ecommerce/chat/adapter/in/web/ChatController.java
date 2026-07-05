package com.beauty.ecommerce.chat.adapter.in.web;

import com.beauty.ecommerce.chat.application.dto.ChatterDTO;
import com.beauty.ecommerce.chat.application.service.AiChatbotService;
import com.beauty.ecommerce.chat.application.service.ChatService;
import com.beauty.ecommerce.chat.domain.entity.ChatMessage;
import com.beauty.ecommerce.common.dto.ApiResponse;
import com.beauty.ecommerce.common.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatService chatService;
    private final CloudinaryService cloudinaryService;
    private final AiChatbotService aiChatbotService;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage) {
        log.info("[CHAT] Tin nhắn từ: {} -> {}: {}", 
            chatMessage.getSenderId(), chatMessage.getRecipientId(), chatMessage.getContent());
        ChatMessage savedMessage = chatService.saveMessage(chatMessage);
        
        // Gửi tới hòm thư người nhận
        String recipientDest = "/topic/chat.messages." + savedMessage.getRecipientId();
        log.info("[CHAT] Đang gửi tới recipient topic: {}", recipientDest);
        messagingTemplate.convertAndSend(recipientDest, savedMessage);
        
        // Gửi tới hòm thư người gửi (xác nhận)
        String senderDest = "/topic/chat.messages." + savedMessage.getSenderId();
        if (!senderDest.equals(recipientDest)) {
            log.info("[CHAT] Đang gửi tới sender topic: {}", senderDest);
            messagingTemplate.convertAndSend(senderDest, savedMessage);
        }
        
        // Thông báo cho admin
        if ("ADMIN".equals(savedMessage.getRecipientId())) {
            log.info("[CHAT] Đang gửi thông báo tới /topic/admin.messages");
            messagingTemplate.convertAndSend("/topic/admin.messages", savedMessage);
        }

        // Phản hồi từ AI nếu gửi tới "AI"
        if ("AI".equals(savedMessage.getRecipientId())) {
            CompletableFuture.runAsync(() -> {
                try {
                    // Lấy lịch sử chat của user với AI
                    List<ChatMessage> history = chatService.getHistory(savedMessage.getSenderId(), "AI");
                    
                    // Sinh câu trả lời từ AI
                    String aiReply = aiChatbotService.generateReply(savedMessage.getContent(), history);
                    
                    // Tạo message phản hồi
                    ChatMessage aiMessage = ChatMessage.builder()
                            .senderId("AI")
                            .senderName("Trợ lý AI Glowzy")
                            .recipientId(savedMessage.getSenderId())
                            .content(aiReply)
                            .type(savedMessage.getType()) // USER hoặc GUEST
                            .isRead(false)
                            .createdAt(Instant.now())
                            .build();
                    
                    ChatMessage savedAiMessage = chatService.saveMessage(aiMessage);
                    
                    // Gửi tin nhắn của AI qua websocket về cho user
                    messagingTemplate.convertAndSend("/topic/chat.messages." + savedMessage.getSenderId(), savedAiMessage);
                } catch (Exception e) {
                    log.error("[CHAT-AI] Lỗi khi chatbot phản hồi: ", e);
                }
            });
        }
    }

    @PostMapping("/api/chat/upload")
    public ResponseEntity<ApiResponse<String>> uploadMedia(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type) {
        String url = cloudinaryService.uploadMedia(file, type);
        return ResponseEntity.ok(ApiResponse.success(url));
    }

    @GetMapping("/api/chat/history/{userId}")
    public ResponseEntity<ApiResponse<List<ChatMessage>>> getChatHistory(
            @PathVariable String userId,
            @RequestParam(value = "recipientId", defaultValue = "ADMIN") String recipientId) {
        List<ChatMessage> history = chatService.getHistory(userId, recipientId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/api/admin/chat/users")
    public ResponseEntity<ApiResponse<List<ChatterDTO>>> getChatUsers() {
        List<ChatterDTO> users = chatService.getUniqueChatters();
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @PatchMapping("/api/chat/read/{senderId}")
    public ResponseEntity<ApiResponse<Void>> markAsRead(@PathVariable String senderId, @RequestParam String recipientId) {
        chatService.markAsRead(senderId, recipientId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
