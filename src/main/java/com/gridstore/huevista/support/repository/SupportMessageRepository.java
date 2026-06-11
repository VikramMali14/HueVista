package com.gridstore.huevista.support.repository;

import com.gridstore.huevista.support.model.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {
    List<SupportMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /** Just the newest message — list views only need a one-line preview. */
    java.util.Optional<SupportMessage> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);
}
