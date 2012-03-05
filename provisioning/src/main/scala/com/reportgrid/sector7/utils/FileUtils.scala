package com.reportgrid.sector7.utils

import java.io.{FileWriter, File}

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/30/11 at 7:57 AM
 */


object FileUtils {
  def writeTempFile(prefix : String, data : String, suffix : Option[String] = None) : File = {
    val tempFile = File.createTempFile(prefix, suffix.getOrElse("tmp"))

    val output = new FileWriter(tempFile)

    try {
      output.write(data)
    } finally {
      output.close()
    }

    tempFile.deleteOnExit()

    tempFile
  }

  def readFile(filename : String, encoding : String = "UTF-8") : String = {
    val input = scala.io.Source.fromFile(filename, encoding)

    try {
      input.getLines().mkString("\n")
    } finally {
      input.close()
    }
  }
}