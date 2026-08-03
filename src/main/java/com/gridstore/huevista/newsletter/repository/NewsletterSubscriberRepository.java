package com.gridstore.huevista.newsletter.repository;

import com.gridstore.huevista.newsletter.model.NewsletterSubscriber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsletterSubscriberRepository extends JpaRepository<NewsletterSubscriber, String> {

    Optional<NewsletterSubscriber> findByEmail(String email);

    Optional<NewsletterSubscriber> findByUnsubscribeToken(String unsubscribeToken);

    long countByStatus(NewsletterSubscriber.Status status);
}
