package com.smile.chunkland.claim;

/** Step-one validator for a whole-land delete over in-memory state only. */
public interface DeleteValidator {

    /**
     * Revalidates one delete against the published snapshot.
     *
     * @throws ClaimRejectedException for fail-closed rejections with a stable
     *         diagnostic key
     */
    ValidatedDelete validate(DeleteRequest request) throws ClaimRejectedException;
}
