package io.tradle.react;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Base64OutputStream;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;

import javax.annotation.Nullable;

public class ImageStoreUtils {

  private static final String TEMP_FILE_PREFIX = "ImageStore_cache";
  private static final int BUFFER_SIZE = 8192;

  /** Compress quality of the output file. */
  private static final int COMPRESS_QUALITY = 90;

  public static String getFileExtensionForType(@Nullable String mimeType) {
    if ("image/png".equals(mimeType)) {
      return ".png";
    }
    if ("image/webp".equals(mimeType)) {
      return ".webp";
    }
    return ".jpg";
  }

  public static Bitmap.CompressFormat getCompressFormatForType(String type) {
    if ("image/png".equals(type)) {
      return Bitmap.CompressFormat.PNG;
    }
    if ("image/webp".equals(type)) {
      return Bitmap.CompressFormat.WEBP;
    }
    return Bitmap.CompressFormat.JPEG;
  }

  public static void writeCompressedBitmapToFile(Bitmap cropped, String mimeType, File tempFile)
          throws IOException {
    OutputStream out = new FileOutputStream(tempFile);
    try {
      cropped.compress(getCompressFormatForType(mimeType), COMPRESS_QUALITY, out);
    } finally {
      if (out != null) {
        out.close();
      }
    }
  }

  public static void writeImageDataToFile(ImageData imageData, File tempFile)
          throws IOException {
    byte[] imageBytes = imageData.bytes;
    String mimeType = imageData.mimeType;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.outMimeType = mimeType;
    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
    writeCompressedBitmapToFile(bitmap, mimeType, tempFile);
  }

  // https://en.wikipedia.org/wiki/List_of_file_signatures
  public static String getMimeTypeFromImageBytes(byte[] image) {
    int firstByte = (int)image[0];
    switch (firstByte) {
      case 255:
        return "image/jpeg";
      case 137:
        return "image/png";
      case 71:
        return "image/gif";
      case 73:
      case 77:
        return "image/tiff";
      case 37:
        return "application/pdf";
      case 208:
        return "application/vnd";
      case 70:
        return "text/plain";
      default:
        return "application/octet-stream";
    }
  }

  public static ImageData parseImageBase64(String imageBase64) {
    byte[] imageBytes = Base64.decode(imageBase64, Base64.DEFAULT);
    String mimeType = getMimeTypeFromImageBytes(imageBytes);
    return new ImageData(imageBytes, mimeType);
  }

  /**
   * Write an image to a temporary file in the cache directory
   *
   * @param imageData image data to use to create file
   */
  private static String createTempFileForImageData(Context context, ImageData imageData)
          throws IOException {
    File tempFile = createTempFile(context, imageData.mimeType);
    writeImageDataToFile(imageData, tempFile);
    return Uri.fromFile(tempFile).toString();
  }

  public static String createTempFileForBase64Image(Context context, String base64)
          throws IOException {
    ImageData imageData = parseImageBase64(base64);
    return createTempFileForImageData(context, imageData);
  }

  public static String createTempFileForImageBytes(Context context, byte[] imageBytes)
          throws IOException {
    String mimeType = getMimeTypeFromImageBytes(imageBytes);
    ImageData imageData = new ImageData(imageBytes, mimeType);
    return createTempFileForImageData(context, imageData);
  }

  public static String copyFileToTempFile(Context context, Uri imageUri, String mimeType)
          throws IOException {
    File source = getFileFromUri(context, imageUri);
    File dest = createTempFile(context, mimeType);
    copyFile(source, dest);
    return dest.getAbsolutePath();
  }

  public static String copyFileToTempFile(Context context, String imageUriString, String mimeType)
          throws IOException {
    return copyFileToTempFile(context, Uri.parse(imageUriString), mimeType);
  }

  public static void copyFile(File sourceFile, File destFile)
          throws IOException {
    FileChannel source = null;
    FileChannel destination = null;

    try {
      source = new FileInputStream(sourceFile).getChannel();
      destination = new FileOutputStream(destFile).getChannel();
      destination.transferFrom(source, 0, source.size());
    } finally {
      if (source != null) {
        closeQuietly(source);
      }
      if (destination != null) {
        closeQuietly(destination);
      }
    }
  }

  /**
   * Create a temporary file in the cache directory on either internal or external storage,
   * whichever is available and has more free space.
   *
   * @param mimeType the MIME type of the file to create (image/*)
   */
  public static File createTempFile(Context context, @Nullable String mimeType)
          throws IOException {
    File externalCacheDir = context.getExternalCacheDir();
    File internalCacheDir = context.getCacheDir();
    File cacheDir;
    if (externalCacheDir == null && internalCacheDir == null) {
      throw new IOException("No cache directory available");
    }
    if (externalCacheDir == null) {
      cacheDir = internalCacheDir;
    }
    else if (internalCacheDir == null) {
      cacheDir = externalCacheDir;
    } else {
      cacheDir = externalCacheDir.getFreeSpace() > internalCacheDir.getFreeSpace() ?
              externalCacheDir : internalCacheDir;
    }
    return File.createTempFile(TEMP_FILE_PREFIX, getFileExtensionForType(mimeType), cacheDir);
  }

  public static @Nullable File getFileFromUri(Context context, Uri uri) {
    if (uri.getScheme().equals("file")) {
      return new File(uri.getPath());
    } else if (uri.getScheme().equals("content")) {
      Cursor cursor = context.getContentResolver()
              .query(uri, new String[] { MediaStore.MediaColumns.DATA }, null, null, null);
      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            String path = cursor.getString(0);
            if (!TextUtils.isEmpty(path)) {
              return new File(path);
            }
          }
        } finally {
          cursor.close();
        }
      }
    }

    return null;
  }

  public static String convertInputStreamToBase64OutputStream(InputStream is) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Base64OutputStream b64os = new Base64OutputStream(baos, Base64.NO_WRAP);
    byte[] buffer = new byte[BUFFER_SIZE];
    int bytesRead;
    try {
      while ((bytesRead = is.read(buffer)) > -1) {
        b64os.write(buffer, 0, bytesRead);
      }
    } finally {
      closeQuietly(b64os); // this also closes baos and flushes the final content to it
    }
    return baos.toString();
  }

  public static boolean isTmpImageFilename(String filename) {
    return filename.startsWith(ImageStoreUtils.TEMP_FILE_PREFIX);
  }

  protected static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      // shhh
    }
  }

}