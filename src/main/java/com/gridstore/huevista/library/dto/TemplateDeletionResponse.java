package com.gridstore.huevista.library.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What a delete actually did — reported back rather than assumed, because the two
 * kinds of delete have very different consequences and the admin should see which
 * one happened.
 */
@Data
@Builder
public class TemplateDeletionResponse {

    /** Templates that were removed, in the order they were asked for. */
    private List<Removed> removed;

    /** Templates that could not be removed, with the reason. */
    private List<Failure> failed;

    /** Total stored files deleted across the whole request. Zero unless purging. */
    private int filesPurged;

    /**
     * Total copies whose photo and masks were deleted out from under them. Zero
     * unless purging — and the number to be uncomfortable about when it is not.
     */
    private long copiesBroken;

    @Data
    @Builder
    public static class Removed {
        private String id;
        private String title;
        private int filesPurged;
        private long copiesBroken;
    }

    @Data
    @Builder
    public static class Failure {
        private String id;
        private String reason;
    }
}
