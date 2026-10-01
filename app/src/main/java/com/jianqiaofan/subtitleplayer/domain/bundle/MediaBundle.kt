package com.jianqiaofan.subtitleplayer.domain.bundle

import com.jianqiaofan.subtitleplayer.domain.cloud.videoHashFileName
import com.jianqiaofan.subtitleplayer.domain.model.isSubtitleFile
import com.jianqiaofan.subtitleplayer.domain.model.mediaStem
import com.jianqiaofan.subtitleplayer.domain.subtitle.subtitleDisplayName
import com.jianqiaofan.subtitleplayer.domain.tags.subtitleOwnerStem
import com.jianqiaofan.subtitleplayer.domain.tags.tagFileNameFor

const val BUNDLE_SUFFIX = ".data"

fun bundleFolderName(videoFileName: String): String = videoFileName + BUNDLE_SUFFIX

fun isBundleFolderName(name: String): Boolean =
    name.endsWith(BUNDLE_SUFFIX) && name.length > BUNDLE_SUFFIX.length

/** Names shown for this video: claimed files beside it, plus subtitles already inside its bundle. */
fun subtitleNamesForVideo(
    videoFileName: String,
    siblingFileNames: List<String>,
    bundleFileNames: List<String>,
): List<String> {
    val stem = mediaStem(videoFileName)
    val inside = bundleFileNames.filter { name ->
        isSubtitleFile(name) && subtitleDisplayName(stem, name) != null
    }
    return (subtitlesBesideVideo(videoFileName, siblingFileNames) + inside).distinct()
}

/** Subtitles beside the video that this video owns. A longer media stem wins. */
fun subtitlesBesideVideo(videoFileName: String, siblingFileNames: List<String>): List<String> {
    val videoStem = mediaStem(videoFileName)
    return siblingFileNames.filter { name ->
        isSubtitleFile(name) && subtitleOwnerStem(name, siblingFileNames) == videoStem
    }
}

data class BundlePlan(
    val moveIntoBundle: List<String>,
    val conflicts: List<String>,
    val deleteBeside: List<String>,
)

/**
 * Files that leave the video's parent and go into `视频全名.data`.
 * Notes and word lists stay beside the video. Same-name subtitles are conflicts, not silent overwrites.
 */
fun planBundleMigration(
    videoFileName: String,
    siblingFileNames: List<String>,
    bundleFileNames: List<String>,
): BundlePlan {
    val bundle = bundleFileNames.toSet()
    val sibling = siblingFileNames.toSet()
    val move = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    for (subtitle in subtitlesBesideVideo(videoFileName, siblingFileNames)) {
        val tag = tagFileNameFor(subtitle)
        if (subtitle in bundle) {
            conflicts += subtitle
        } else {
            move += subtitle
            if (tag in sibling && tag !in bundle) move += tag
        }
    }
    val hashName = videoHashFileName(videoFileName)
    val deleteBeside = mutableListOf<String>()
    if (hashName in sibling && hashName !in bundle) move += hashName
    if (hashName in sibling && hashName in bundle) deleteBeside += hashName
    return BundlePlan(move, conflicts, deleteBeside)
}

data class ConflictResolution(
    val deleteInBundle: List<String>,
    val deleteBeside: List<String>,
    val moveIntoBundle: List<String>,
)

/** The kept copy stays in the bundle. The other subtitle and its tag file are deleted. */
fun resolveNameConflict(
    subtitleFileName: String,
    keepBundleCopy: Boolean,
    bundleHasTag: Boolean,
    besideHasTag: Boolean,
): ConflictResolution {
    val tag = tagFileNameFor(subtitleFileName)
    return if (keepBundleCopy) {
        ConflictResolution(
            deleteInBundle = emptyList(),
            deleteBeside = listOf(subtitleFileName) + if (besideHasTag) listOf(tag) else emptyList(),
            moveIntoBundle = emptyList(),
        )
    } else {
        ConflictResolution(
            deleteInBundle = listOf(subtitleFileName) + if (bundleHasTag) listOf(tag) else emptyList(),
            deleteBeside = emptyList(),
            moveIntoBundle = listOf(subtitleFileName) + if (besideHasTag) listOf(tag) else emptyList(),
        )
    }
}
