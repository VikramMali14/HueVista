package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    /**
     * Spring calls this after Google returns the user's profile.
     * We upsert the user into our DB, then wrap them in a DefaultOAuth2User
     * so the success handler can retrieve our internal user ID.
     */
    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest); // fetches /userinfo from Google
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String email      = (String) attributes.get("email");
        String name       = (String) attributes.get("name");
        String picture    = (String) attributes.get("picture");
        String providerId = (String) attributes.get("sub");  // Google's unique user ID

        User user = userRepository.findByEmail(email)
                .map(existing -> updateExistingUser(existing, name, picture, providerId))
                .orElseGet(() -> createOAuth2User(email, name, picture, providerId));

        log.info("OAuth2 user loaded: {} via Google", email);

        // Pass our internal id forward so the success handler can build the JWT
        return new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of(
                        "id",      user.getId(),
                        "email",   user.getEmail(),
                        "name",    user.getName(),
                        "picture", user.getPicture() != null ? user.getPicture() : ""
                ),
                "email"
        );
    }

    private User createOAuth2User(String email, String name, String picture, String providerId) {
        User user = User.builder()
                .email(email)
                .name(name)
                .picture(picture)
                .provider(AuthProvider.GOOGLE)
                .providerId(providerId)
                .emailVerified(true)
                .build();
        return userRepository.save(user);
    }

    private User updateExistingUser(User user, String name, String picture, String providerId) {
        user.setName(name);
        user.setPicture(picture);
        user.setProviderId(providerId);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }
}
