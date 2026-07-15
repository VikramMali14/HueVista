package com.gridstore.huevista.support.repository;

import com.gridstore.huevista.support.model.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {

    /** Id is the tiebreak: a user message and the AI reply saved in the same
     *  transaction can share a createdAt, and an unordered tie can render the
     *  reply ABOVE the question. UUIDs aren't chronological, but a stable order
     *  beats one that changes per query. */
    List<SupportMessage> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);

    /** Just the newest message — list views only need a one-line preview. */
    Optional<SupportMessage> findTopByConversationIdOrderByCreatedAtDescIdDesc(String conversationId);
}
