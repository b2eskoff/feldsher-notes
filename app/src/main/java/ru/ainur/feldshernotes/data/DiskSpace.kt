package ru.ainur.feldshernotes.data

import android.annotation.SuppressLint
import java.io.File

object DiskSpace {
    // A real-time recorder needs already-free blocks. Reclaiming caches during capture
    // can block the reader and lose input; stop safely with a reserve instead.
    @SuppressLint("UsableSpace")
    fun available(file: File): Long = file.usableSpace
}
