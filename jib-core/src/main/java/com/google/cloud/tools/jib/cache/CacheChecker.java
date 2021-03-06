/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.cache;

import com.google.cloud.tools.jib.image.DescriptorDigest;
import com.google.cloud.tools.jib.image.ImageLayers;
import com.google.cloud.tools.jib.image.LayerPropertyNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// TODO: Put into CacheReader and remove this class.
/** Checks if cached data is outdated. */
public class CacheChecker {

  private final Cache cache;

  /**
   * @return the last modified time for the file at {@code path}. Recursively finds the most recent
   *     last modified time for all subfiles if the file is a directory.
   */
  private static FileTime getLastModifiedTime(Path path) throws IOException {
    FileTime lastModifiedTime = Files.getLastModifiedTime(path);

    if (Files.isReadable(path)) {
      try {
        Optional<FileTime> maxLastModifiedTime =
            Files.walk(path)
                .map(
                    subFilePath -> {
                      try {
                        return Files.getLastModifiedTime(subFilePath);

                      } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                      }
                    })
                .max(FileTime::compareTo);

        if (!maxLastModifiedTime.isPresent()) {
          throw new IllegalStateException(
              "Could not get last modified time for all files in directory '" + path + "'");
        }
        if (maxLastModifiedTime.get().compareTo(lastModifiedTime) > 0) {
          lastModifiedTime = maxLastModifiedTime.get();
        }

      } catch (UncheckedIOException ex) {
        throw ex.getCause();
      }
    }

    return lastModifiedTime;
  }

  public CacheChecker(Cache cache) {
    this.cache = cache;
  }

  /** @return the cached layer with digest {@code layerDigest} */
  public CachedLayer getLayer(DescriptorDigest layerDigest) throws LayerPropertyNotFoundException {
    return cache.getMetadata().getLayers().get(layerDigest);
  }

  /** @return the most up-to-date layer that is built from the {@code sourceFiles}. */
  public CachedLayer getUpToDateLayerBySourceFiles(List<Path> sourceFiles)
      throws IOException, CacheMetadataCorruptedException {
    // Grabs all the layers that have matching source files.
    ImageLayers<CachedLayerWithMetadata> cachedLayersWithSourceFiles =
        cache.getMetadata().filterLayers().bySourceFiles(sourceFiles).filter();
    if (cachedLayersWithSourceFiles.isEmpty()) {
      return null;
    }

    FileTime sourceFilesLastModifiedTime = FileTime.from(Instant.MIN);
    for (Path path : sourceFiles) {
      FileTime lastModifiedTime = getLastModifiedTime(path);
      if (lastModifiedTime.compareTo(sourceFilesLastModifiedTime) > 0) {
        sourceFilesLastModifiedTime = lastModifiedTime;
      }
    }

    // Checks if at least one of the matched layers is up-to-date.
    for (CachedLayerWithMetadata cachedLayer : cachedLayersWithSourceFiles) {
      if (sourceFilesLastModifiedTime.compareTo(cachedLayer.getMetadata().getLastModifiedTime())
          <= 0) {
        // This layer is an up-to-date layer.
        return cachedLayer;
      }
    }

    return null;
  }

  // TODO: REMOVE
  /**
   * Checks all cached layers built from the source files to see if the source files have been
   * modified since the newest layer build.
   *
   * @param sourceFiles the source files to check
   * @return true if no cached layer exists that are up-to-date with the source files; false
   *     otherwise.
   */
  @Deprecated
  public boolean areSourceFilesModified(List<Path> sourceFiles)
      throws IOException, CacheMetadataCorruptedException {
    // Grabs all the layers that have matching source files.
    ImageLayers<CachedLayerWithMetadata> cachedLayersWithSourceFiles =
        cache.getMetadata().filterLayers().bySourceFiles(sourceFiles).filter();
    if (cachedLayersWithSourceFiles.isEmpty()) {
      return true;
    }

    FileTime sourceFilesLastModifiedTime = FileTime.from(Instant.MIN);
    for (Path path : sourceFiles) {
      FileTime lastModifiedTime = getLastModifiedTime(path);
      if (lastModifiedTime.compareTo(sourceFilesLastModifiedTime) > 0) {
        sourceFilesLastModifiedTime = lastModifiedTime;
      }
    }

    // Checks if at least one of the matched layers is up-to-date.
    for (CachedLayerWithMetadata cachedLayer : cachedLayersWithSourceFiles) {
      if (sourceFilesLastModifiedTime.compareTo(cachedLayer.getMetadata().getLastModifiedTime())
          <= 0) {
        // This layer is an up-to-date layer.
        return false;
      }
    }

    return true;
  }
}
