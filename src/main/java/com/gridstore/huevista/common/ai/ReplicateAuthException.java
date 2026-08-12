package com.gridstore.huevista.common.ai;

/**
 * Replicate refused our token (401/403), which is a statement about the account
 * rather than about this model or this photo.
 *
 * <p>It matters as its own type because the cleaning chain contains several Replicate
 * models: without this, a dead token would be discovered four times over — once per
 * model — before the chain reached a provider on a different platform. The caller
 * catches this to abandon the rest of Replicate and go straight to Google.
 */
public class ReplicateAuthException extends ImageEditException {

    public ReplicateAuthException(String message) {
        // FAILOVER, not GIVE_UP: nothing is wrong with the image, and a provider that
        // isn't Replicate can still deliver the clean.
        super(message, Disposition.FAILOVER);
    }
}
