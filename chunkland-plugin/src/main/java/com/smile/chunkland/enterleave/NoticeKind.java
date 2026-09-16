package com.smile.chunkland.enterleave;

/**
 * Kind of a single enter-leave prompt. Land-level kinds carry the land name;
 * sub-land kinds carry both the parent land name and the sub-land name.
 */
public enum NoticeKind {
    ENTER_LAND,
    LEAVE_LAND,
    ENTER_SUB,
    LEAVE_SUB
}
