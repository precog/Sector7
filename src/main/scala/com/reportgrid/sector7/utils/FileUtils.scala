package com.reportgrid.sector7.utils

import java.io.{FileWriter, File}

/**
 * Copyright 2011, ReportGrid, Inc.
 *
 * Created by dchenbecker on 10/30/11 at 7:57 AM
 */


object FileUtils {
  def writeTempFile(prefix : String, data : String) : File = {
    val tempFile = File.createTempFile(prefix, "tmp")

    val output = new FileWriter(tempFile)

    output.write(data)
    output.close()

    tempFile.deleteOnExit()

    tempFile
  }
}