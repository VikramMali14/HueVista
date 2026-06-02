package com.gridstore.huevista.support.repository;

import com.gridstore.huevista.support.model.Conversation;
import com.gridstore.huevista.support.model.ConversationStatus;
import com.gridstore.huevista.support.model.SupportChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(String userId);
    Optional<Conversation> findByIdAndUserId(String id, String userId);
    List<Conversation> findByStatusOrderByUpdatedAtDesc(ConversationStatus status);

    /** Most recent non-resolved conversation for an external contact on a channel. */
    Optional<Conversation> findFirstByChannelAndContactChannelIdAndStatusNotOrderByUpdatedAtDesc(
            SupportChannel channel, String contactChannelId, ConversationStatus status);
}
