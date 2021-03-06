/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.shell;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.shell.PathExceptions.PathIOException;
import org.apache.hadoop.fs.shell.PathExceptions.PathIsDirectoryException;
import org.apache.hadoop.fs.shell.PathExceptions.PathIsNotDirectoryException;
import org.apache.hadoop.fs.shell.PathExceptions.PathNotFoundException;

/**
 * Encapsulates a Path (path), its FileStatus (stat), and its FileSystem (fs).
 * The stat field will be null if the path does not exist.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable

public class PathData {
  protected final URI uri;
  public final FileSystem fs;
  public final Path path;
  public FileStatus stat;
  public boolean exists;

  /**
   * Creates an object to wrap the given parameters as fields.  The string
   * used to create the path will be recorded since the Path object does not
   * return exactly the same string used to initialize it
   * @param pathString a string for a path
   * @param conf the configuration file
   * @throws IOException if anything goes wrong...
   */
  public PathData(String pathString, Configuration conf) throws IOException {
    this(FileSystem.get(URI.create(pathString), conf), pathString);
  }
  
  /**
   * Creates an object to wrap the given parameters as fields.  The string
   * used to create the path will be recorded since the Path object does not
   * return exactly the same string used to initialize it
   * @param localPath a local File
   * @param conf the configuration file
   * @throws IOException if anything goes wrong...
   */
  public PathData(File localPath, Configuration conf) throws IOException {
    this(FileSystem.getLocal(conf), localPath.toString());
  }

  /**
   * Looks up the file status for a path.  If the path
   * doesn't exist, then the status will be null
   * @param fs the FileSystem for the path
   * @param pathString a string for a path 
   * @throws IOException if anything goes wrong
   */
  private PathData(FileSystem fs, String pathString) throws IOException {
    this(fs, pathString, lookupStat(fs, pathString, true));
  }

  /**
   * Creates an object to wrap the given parameters as fields.  The string
   * used to create the path will be recorded since the Path object does not
   * return exactly the same string used to initialize it.
   * @param fs the FileSystem
   * @param pathString a String of the path
   * @param stat the FileStatus (may be null if the path doesn't exist)
   */
  private PathData(FileSystem fs, String pathString, FileStatus stat)
  throws IOException {
    this.fs = fs;
    this.uri = stringToUri(pathString);
    this.path = fs.makeQualified(new Path(uri));
    setStat(stat);
  }

  // need a static method for the ctor above
  /**
   * Get the FileStatus info
   * @param ignoreFNF if true, stat will be null if the path doesn't exist
   * @return FileStatus for the given path
   * @throws IOException if anything goes wrong
   */
  private static
  FileStatus lookupStat(FileSystem fs, String pathString, boolean ignoreFNF)
  throws IOException {
    FileStatus status = null;
    try {
      status = fs.getFileStatus(new Path(pathString));
    } catch (FileNotFoundException e) {
      if (!ignoreFNF) throw new PathNotFoundException(pathString);
    }
    // TODO: should consider wrapping other exceptions into Path*Exceptions
    return status;
  }
  
  private void setStat(FileStatus stat) {
    this.stat = stat;
    exists = (stat != null);
  }

  /**
   * Updates the paths's file status
   * @return the updated FileStatus
   * @throws IOException if anything goes wrong...
   */
  public FileStatus refreshStatus() throws IOException {
    FileStatus status = null;
    try {
      status = lookupStat(fs, toString(), false);
    } finally {
      // always set the status.  the caller must get the correct result
      // if it catches the exception and later interrogates the status
      setStat(status);
    }
    return status;
  }

  protected enum FileTypeRequirement {
    SHOULD_NOT_BE_DIRECTORY, SHOULD_BE_DIRECTORY
  };

  /**
   * Ensure that the file exists and if it is or is not a directory
   * @param typeRequirement Set it to the desired requirement.
   * @throws PathIOException if file doesn't exist or the type does not match
   * what was specified in typeRequirement.
   */
  private void checkIfExists(FileTypeRequirement typeRequirement) 
  throws PathIOException {
    if (!exists) {
      throw new PathNotFoundException(toString());      
    }

    if ((typeRequirement == FileTypeRequirement.SHOULD_BE_DIRECTORY)
       && !stat.isDirectory()) {
      throw new PathIsNotDirectoryException(toString());
    } else if ((typeRequirement == FileTypeRequirement.SHOULD_NOT_BE_DIRECTORY)
              && stat.isDirectory()) {
      throw new PathIsDirectoryException(toString());
    }
  }
  
  /**
   * Returns a temporary file for this PathData with the given extension.
   * The file will be deleted on exit.
   * @param extension for the temporary file
   * @return PathData
   * @throws IOException shouldn't happen
   */
  public PathData createTempFile(String extension) throws IOException {
    PathData tmpFile = new PathData(fs, uri+"._COPYING_");
    fs.deleteOnExit(tmpFile.path);
    return tmpFile;
  }

  /**
   * Returns a list of PathData objects of the items contained in the given
   * directory.
   * @return list of PathData objects for its children
   * @throws IOException if anything else goes wrong...
   */
  public PathData[] getDirectoryContents() throws IOException {
    checkIfExists(FileTypeRequirement.SHOULD_BE_DIRECTORY);
    FileStatus[] stats = fs.listStatus(path);
    PathData[] items = new PathData[stats.length];
    for (int i=0; i < stats.length; i++) {
      // preserve relative paths
      String child = getStringForChildPath(stats[i].getPath());
      items[i] = new PathData(fs, child, stats[i]);
    }
    return items;
  }

  /**
   * Creates a new object for a child entry in this directory
   * @param child the basename will be appended to this object's path
   * @return PathData for the child
   * @throws IOException if this object does not exist or is not a directory
   */
  public PathData getPathDataForChild(PathData child) throws IOException {
    checkIfExists(FileTypeRequirement.SHOULD_BE_DIRECTORY);
    return new PathData(fs, getStringForChildPath(child.path));
  }

  /**
   * Given a child of this directory, use the directory's path and the child's
   * basename to construct the string to the child.  This preserves relative
   * paths since Path will fully qualify.
   * @param child a path contained within this directory
   * @return String of the path relative to this directory
   */
  private String getStringForChildPath(Path childPath) {
    String basename = childPath.getName();
    if (Path.CUR_DIR.equals(toString())) {
      return basename;
    }
    // check getPath() so scheme slashes aren't considered part of the path
    String separator = uri.getPath().endsWith(Path.SEPARATOR)
        ? "" : Path.SEPARATOR;
    return uri + separator + basename;
  }
  
  protected enum PathType { HAS_SCHEME, SCHEMELESS_ABSOLUTE, RELATIVE };
  
  /**
   * Expand the given path as a glob pattern.  Non-existent paths do not
   * throw an exception because creation commands like touch and mkdir need
   * to create them.  The "stat" field will be null if the path does not
   * exist.
   * @param pattern the pattern to expand as a glob
   * @param conf the hadoop configuration
   * @return list of {@link PathData} objects.  if the pattern is not a glob,
   * and does not exist, the list will contain a single PathData with a null
   * stat 
   * @throws IOException anything else goes wrong...
   */
  public static PathData[] expandAsGlob(String pattern, Configuration conf)
  throws IOException {
    Path globPath = new Path(pattern);
    FileSystem fs = globPath.getFileSystem(conf);    
    FileStatus[] stats = fs.globStatus(globPath);
    PathData[] items = null;
    
    if (stats == null) {
      // not a glob & file not found, so add the path with a null stat
      items = new PathData[]{ new PathData(fs, pattern, null) };
    } else {
      // figure out what type of glob path was given, will convert globbed
      // paths to match the type to preserve relativity
      PathType globType;
      URI globUri = globPath.toUri();
      if (globUri.getScheme() != null) {
        globType = PathType.HAS_SCHEME;
      } else if (new File(globUri.getPath()).isAbsolute()) {
        globType = PathType.SCHEMELESS_ABSOLUTE;
      } else {
        globType = PathType.RELATIVE;
      }

      // convert stats to PathData
      items = new PathData[stats.length];
      int i=0;
      for (FileStatus stat : stats) {
        URI matchUri = stat.getPath().toUri();
        String globMatch = null;
        switch (globType) {
          case HAS_SCHEME: // use as-is, but remove authority if necessary
            if (globUri.getAuthority() == null) {
              matchUri = removeAuthority(matchUri);
            }
            globMatch = matchUri.toString();
            break;
          case SCHEMELESS_ABSOLUTE: // take just the uri's path
            globMatch = matchUri.getPath();
            break;
          case RELATIVE: // make it relative to the current working dir
            URI cwdUri = fs.getWorkingDirectory().toUri();
            globMatch = relativize(cwdUri, matchUri, stat.isDirectory());
            break;
        }
        items[i++] = new PathData(fs, globMatch, stat);
      }
    }
    return items;
  }

  private static URI removeAuthority(URI uri) {
    try {
      uri = new URI(
          uri.getScheme(), "",
          uri.getPath(), uri.getQuery(), uri.getFragment()
      );
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e.getLocalizedMessage());
    }
    return uri;
  }
  
  private static String relativize(URI cwdUri, URI srcUri, boolean isDir) {
    String uriPath = srcUri.getPath();
    String cwdPath = cwdUri.getPath();
    if (cwdPath.equals(uriPath)) {
      return Path.CUR_DIR;
    }

    // find common ancestor
    int lastSep = findLongestDirPrefix(cwdPath, uriPath, isDir);
    
    StringBuilder relPath = new StringBuilder();    
    // take the remaining path fragment after the ancestor
    if (lastSep < uriPath.length()) {
      relPath.append(uriPath.substring(lastSep+1));
    }

    // if cwd has a path fragment after the ancestor, convert them to ".."
    if (lastSep < cwdPath.length()) {
      while (lastSep != -1) {
        if (relPath.length() != 0) relPath.insert(0, Path.SEPARATOR);
        relPath.insert(0, "..");
        lastSep = cwdPath.indexOf(Path.SEPARATOR, lastSep+1);
      }
    }
    return relPath.toString();
  }

  private static int findLongestDirPrefix(String cwd, String path, boolean isDir) {
    // add the path separator to dirs to simplify finding the longest match
    if (!cwd.endsWith(Path.SEPARATOR)) {
      cwd += Path.SEPARATOR;
    }
    if (isDir && !path.endsWith(Path.SEPARATOR)) {
      path += Path.SEPARATOR;
    }

    // find longest directory prefix 
    int len = Math.min(cwd.length(), path.length());
    int lastSep = -1;
    for (int i=0; i < len; i++) {
      if (cwd.charAt(i) != path.charAt(i)) break;
      if (cwd.charAt(i) == Path.SEPARATOR_CHAR) lastSep = i;
    }
    return lastSep;
  }
  
  /**
   * Returns the printable version of the path that is either the path
   * as given on the commandline, or the full path
   * @return String of the path
   */
  public String toString() {
    String scheme = uri.getScheme();
    // No interpretation of symbols. Just decode % escaped chars.
    String decodedRemainder = uri.getSchemeSpecificPart();

    if (scheme == null) {
      return decodedRemainder;
    } else {
      StringBuilder buffer = new StringBuilder();
      buffer.append(scheme);
      buffer.append(":");
      buffer.append(decodedRemainder);
      return buffer.toString();
    }
  }
  
  /**
   * Get the path to a local file
   * @return File representing the local path
   * @throws IllegalArgumentException if this.fs is not the LocalFileSystem
   */
  public File toFile() {
    if (!(fs instanceof LocalFileSystem)) {
       throw new IllegalArgumentException("Not a local path: " + path);
    }
    return ((LocalFileSystem)fs).pathToFile(path);
  }

  /** Construct a URI from a String with unescaped special characters
   *  that have non-standard sematics. e.g. /, ?, #. A custom parsing
   *  is needed to prevent misbihaviors.
   *  @param pathString The input path in string form
   *  @return URI
   */
  private static URI stringToUri(String pathString) {
    // We can't use 'new URI(String)' directly. Since it doesn't do quoting
    // internally, the internal parser may fail or break the string at wrong
    // places. Use of multi-argument ctors will quote those chars for us,
    // but we need to do our own parsing and assembly.
    
    // parse uri components
    String scheme = null;
    String authority = null;

    int start = 0;

    // parse uri scheme, if any
    int colon = pathString.indexOf(':');
    int slash = pathString.indexOf('/');
    if (colon > 0 && (slash == colon +1)) {
      // has a non zero-length scheme
      scheme = pathString.substring(0, colon);
      start = colon + 1;
    }

    // parse uri authority, if any
    if (pathString.startsWith("//", start) &&
        (pathString.length()-start > 2)) {
      start += 2;
      int nextSlash = pathString.indexOf('/', start);
      int authEnd = nextSlash > 0 ? nextSlash : pathString.length();
      authority = pathString.substring(start, authEnd);
      start = authEnd;
    }

    // uri path is the rest of the string. ? or # are not interpreated,
    // but any occurrence of them will be quoted by the URI ctor.
    String path = pathString.substring(start, pathString.length());

    // Construct the URI
    try {
      return new URI(scheme, authority, path, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

}
