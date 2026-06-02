package com.gridstore.huevista.support.repository;

import com.gridstore.huevista.support.model.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {
    List<SupportMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
