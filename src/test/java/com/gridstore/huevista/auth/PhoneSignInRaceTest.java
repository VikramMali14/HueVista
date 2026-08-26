package com.gridstore.huevista.auth;

import com.gridstore.huevista.auth.dto.AuthResponse;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.auth.service.AuthService;
import com.gridstore.huevista.auth.service.FirebaseTokenVerifier;
import com.gridstore.huevista.auth.service.PhoneAuthService;
import com.gridstore.huevista.common.audit.AuditService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two people — or one person double-tapping a button on a slow connection — signing in
 * with the same NEVER-SEEN-BEFORE number at the same moment.
 *
 * <p>`users.email` is UNIQUE and a phone account's address is derived from its number,
 * so the loser of that race fails on the constraint. It must land on the account the
 * winner opened, not on a 500: the outcome it asked for has already happened.
 *
 * <p>Driven through a stubbed repository rather than real threads, because the point is
 * to pin the recovery path deterministically — a timing-dependent test that usually
 * fails to reproduce the race would pin nothing at all.
 */
class PhoneSignInRaceTest {

    private static final String PROJECT = "huevista-test";
    private static final String PHONE = "+919876500001";

    private static FirebaseCerts certs;

    @BeforeAll
    static void start() throws Exception {
        certs = new FirebaseCerts();
    }

    @AfterAll
    static void stop() {
        certs.close();
    }

    /** Runs the callback inline — enough for a TransactionTemplate under test. */
    private static PlatformTransactionManager inlineTransactions() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return tm;
    }

    @Test
    void the_loser_of_a_first_sign_in_race_lands_on_the_winners_account() {
        UserRepository users = mock(UserRepository.class);
        AuthService auth = mock(AuthService.class);

        User winners = User.builder()
                .id("winner-id").name("Asha").email("ph-919876500001@customers.huevista.local")
                .provider(AuthProvider.PHONE).role(UserRole.CUSTOMER)
                .phoneNumber(PHONE).phoneVerified(true).build();

        // First pass: nobody owns the number. Second pass (the retry, in a fresh
        // transaction): the other request has committed, so the account is there.
        AtomicInteger lookups = new AtomicInteger();
        when(users.findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc(PHONE))
                .thenAnswer(inv -> lookups.incrementAndGet() == 1 ? List.of() : List.of(winners));

        // ...so the first pass tries to insert, and collides on the unique address.
        when(users.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        AuthResponse expected = AuthResponse.builder().accessToken("tok").build();
        when(auth.buildAuthResponse(winners)).thenReturn(expected);

        PhoneAuthService service = new PhoneAuthService(
                new FirebaseTokenVerifier(PROJECT, certs.url()),
                users, auth, mock(AuditService.class), inlineTransactions());

        AuthResponse response = service.signIn(token(), null);

        assertThat(response).isSameAs(expected);
        assertThat(lookups.get()).as("exactly one retry, not a loop").isEqualTo(2);
    }

    @Test
    void a_constraint_failure_that_is_NOT_the_race_still_fails() {
        UserRepository users = mock(UserRepository.class);
        AuthService auth = mock(AuthService.class);

        // Nobody ever owns the number, so the retry collides again. That is a real
        // problem and must surface — quietly swallowing it would hide a broken schema
        // behind an endless "please try again".
        when(users.findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc(anyString()))
                .thenReturn(List.of());
        when(users.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("something else entirely"));

        PhoneAuthService service = new PhoneAuthService(
                new FirebaseTokenVerifier(PROJECT, certs.url()),
                users, auth, mock(AuditService.class), inlineTransactions());

        assertThatThrownBy(() -> service.signIn(token(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String token() {
        return Jwts.builder()
                .header().keyId(FirebaseCerts.KID).and()
                .subject("firebase-uid-race")
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience().add(PROJECT).and()
                .claim("phone_number", PHONE)
                .claim("firebase", FirebaseCerts.phoneProviderClaim(PHONE))
                .issuedAt(Date.from(Instant.now().minusSeconds(10)))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(certs.privateKey(), Jwts.SIG.RS256)
                .compact();
    }
}
