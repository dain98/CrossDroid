package de.radicalfishgames.crosscode

import java.io.File

/** @author X1nto */
infix fun File.or(another: File): File {
    return if (exists()) this else another
}