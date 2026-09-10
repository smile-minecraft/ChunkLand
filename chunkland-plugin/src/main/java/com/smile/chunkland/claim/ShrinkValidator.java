package com.smile.chunkland.claim;

/**
 * Step-one validator seam for land shrink.
 */
public interface ShrinkValidator {

    ValidatedShrink validate(ShrinkRequest request) throws ClaimRejectedException;
}
