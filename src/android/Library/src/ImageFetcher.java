/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.synconset;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

/**
 * This helper class download images from the Internet and binds those with the
 * provided ImageView.
 * <p/>
 * <p>
 * It requires the INTERNET permission, which should be added to your
 * application's manifest file.
 * </p>
 * <p/>
 * A local cache of downloaded images is maintained internally to improve
 * performance.
 */
public class ImageFetcher {

    private static String LogTag = "ImageFetcher";

    private int colWidth;
    private long origId;
    private ExecutorService executor;
    private static Random random = new Random();
    private static int[] defaultColors = new int[]{
            Color.rgb(230, 0, 126),
            Color.rgb(150, 193, 31),
            Color.rgb(0, 159, 227)
    };

    public ImageFetcher() {
        executor = Executors.newCachedThreadPool();
    }

    public void fetch(Integer id, ImageView imageView, int colWidth, int rotate) {
        resetPurgeTimer();
        this.colWidth = colWidth;
        this.origId = id;
        Bitmap bitmap = getBitmapFromCache(id);

        if (bitmap == null) {
            forceDownload(id, imageView, rotate);
        } else {
            cancelPotentialDownload(id, imageView);
            imageView.setImageBitmap(bitmap);
        }
    }

    /**
     * Same as download but the image is always downloaded and the cache is not
     * used. Kept private at the moment as its interest is not clear.
     */
    private void forceDownload(Integer position, ImageView imageView, int rotate) {
        if (position == null) {
            imageView.setImageDrawable(null);
            return;
        }

        if (cancelPotentialDownload(position, imageView)) {
            BitmapFetcherTask task = new BitmapFetcherTask(imageView.getContext(), imageView, rotate);
            DownloadedDrawable downloadedDrawable = new DownloadedDrawable(imageView.getContext(), task, origId);
            imageView.setImageDrawable(downloadedDrawable);
            imageView.setMinimumHeight(colWidth);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                task.executeOnExecutor(executor, position);
            } else {
                try {
                    task.execute(position);
                } catch (RejectedExecutionException e) {
                    // Oh :(
                }
            }

        }
    }

    /**
     * Returns true if the current download has been canceled or if there was no
     * download in progress on this image view. Returns false if the download in
     * progress deals with the same url. The download is not stopped in that
     * case.
     */
    private static boolean cancelPotentialDownload(Integer position, ImageView imageView) {
        BitmapFetcherTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
        long origId = getOrigId(imageView);

        if (bitmapDownloaderTask != null) {
            Integer bitmapPosition = bitmapDownloaderTask.position;
            if ((bitmapPosition == null) || (!bitmapPosition.equals(position))) {
                // Log.d("DAVID", "Canceling...");
                MediaStore.Images.Thumbnails.cancelThumbnailRequest(imageView.getContext().getContentResolver(),
                        origId, 12345);
                bitmapDownloaderTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * @param imageView Any imageView
     * @return Retrieve the currently active download task (if any) associated
     * with this imageView. null if there is no such task.
     */
    private static BitmapFetcherTask getBitmapDownloaderTask(ImageView imageView) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof DownloadedDrawable) {
                DownloadedDrawable downloadedDrawable = (DownloadedDrawable) drawable;
                return downloadedDrawable.getBitmapDownloaderTask();
            }
        }
        return null;
    }

    private static long getOrigId(ImageView imageView) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof DownloadedDrawable) {
                DownloadedDrawable downloadedDrawable = (DownloadedDrawable) drawable;
                return downloadedDrawable.getOrigId();
            }
        }
        return -1;
    }

    /**
     * The actual AsyncTask that will asynchronously download the image.
     */
    class BitmapFetcherTask extends AsyncTask<Integer, Void, Bitmap> {
        private Integer position;
        private final WeakReference<ImageView> imageViewReference;
        private final Context mContext;
        private final int rotate;

        public BitmapFetcherTask(Context context, ImageView imageView, int rotate) {
            imageViewReference = new WeakReference<ImageView>(imageView);
            mContext = context;
            this.rotate = rotate;
        }

        /**
         * Actual download method.
         */
        @Override
        protected Bitmap doInBackground(Integer... params) {
            try {
                position = params[0];
                if (isCancelled()) {
                    return null;
                }

                // Gets the bitmap of the thumbnail.
                /*
                Bitmap thumb = MediaStore.Images.Thumbnails.getThumbnail(mContext.getContentResolver(), position, 12345,
	                    MediaStore.Images.Thumbnails.MICRO_KIND, null);
                */
                // Tries manually creating the thumbnail instead of using "MediaStore.Images.Thumbnails.getThumbnail"
                // as it looks like generating those thumbnails is really costly and creates huge files.
                return this.getThumbnail();

                /*
                if (isCancelled()) {
                    return null;
                }
                if (thumb == null) {
                    return null;
                } else {
                    if (isCancelled()) {
                        return null;
                    } else {
                        if (rotate != 0) {
                            Matrix matrix = new Matrix();
                            matrix.setRotate(rotate);
                            thumb = Bitmap.createBitmap(thumb, 0, 0, thumb.getWidth(), thumb.getHeight(), matrix, true);
                        }
                        return thumb;
                    }
                }
                */
            } catch (OutOfMemoryError error) {
                clearCache();

                String errorMessage = error.getMessage();
                Log.e(LogTag, "IO Exception", error);
                this.logStuff(errorMessage);

                return null;
            }

        }

        private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
            // Raw height and width of image
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;

            if (height > reqHeight || width > reqWidth) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;

                // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                // height and width larger than the requested height and width.
                while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
                    inSampleSize *= 2;
                }
            }

            return inSampleSize;
        }


        private Bitmap getThumbnail() {
            final String pngExtension = "png";
            final int quality = 75;
            final int thumbnailSize = 100;
            final String thumbnailsFolderName = ".thumbs";

            // Avoids using getThumbnail, as it looks like it ends up creating huge files that can take quite some
            // time to create, if the thumbnails are not yet generated.
            Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Integer.toString(position));
            String filePath = null;

            // Can't simply get the file like that as uri.getPath don't give use the real path.
            // File imageFile = new File(uri.getPath());

            // Gets the size of the image, by setting inJustDecodeBounds to true, then computes
            // the sample size to use when loading the image (to avoid loading too much).
            try {
                ContentResolver contentResolver = mContext.getContentResolver();

                // Gets the name of the file (useful to be able to create or look for the thumbnail).
                //String filePath = null;
                //String[] projection = {MediaStore.Images.Media.DATA};
                //Cursor cursor = contentResolver.query(uri, projection, null, null, null);
                //int columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                //if (columnIndex >= 0 && cursor.moveToFirst()) {
                //    cursor.moveToFirst();
                //    filePath = cursor.getString(columnIndex);
                //}
                //cursor.close();
                filePath = FileHelper.getPath(mContext, uri);

                // If there's an error we just return.
                if (filePath == null) {
                    return null;
                }

                // Gets the name of the thumbnail to retrieve or create.
                File currentFile = new File(filePath);
                File newFileFolder = new File(currentFile.getParent(), thumbnailsFolderName);

                // Creates the folder if necessary. NOTE that we may not be able to write on that
                // location. So we 1st try to create our folder, if we can't, we try to create
                // a folder in the public directory.
                newFileFolder.mkdirs();
                if (!newFileFolder.isDirectory()) {
                    newFileFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), thumbnailsFolderName);
                    newFileFolder.mkdirs();
                    if (!newFileFolder.isDirectory()) {
                        return null;
                    }

                    // We create a sub folder, in case several pictures in different folders have the same name.
                    newFileFolder = new File(newFileFolder, currentFile.getParent());
                    newFileFolder.mkdirs();
                }

                File newFile = new File(newFileFolder, currentFile.getName());

                if (isCancelled()) {
                    return null;
                }

                if (newFile.exists()) {
                    // The thumbnail already exists, just read its.
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPurgeable = true;
                    Bitmap bitmap = BitmapFactory.decodeFile(newFile.getAbsolutePath(), options);

                    // If the read bitmap is not null, returns it,
                    // else we delete the file and recreate the thumbnail.
                    if (bitmap != null) {
                        return bitmap;
                    } else {
                        newFile.delete();
                    }
                }

                // The thumbnail doesn't exist yet, we'll create it.

                // Gets stream from the image URI.
                InputStream stream = contentResolver.openInputStream(uri);
                // stream.mark(Integer.MAX_VALUE); Can't use it, we'll have to recreate the stream.

                // Gets image size.
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(stream, null, options);

                // Reopens the stream (can't use stream.reset) for when we'll really read the content).
                stream.close();
                if (isCancelled()) {
                    return null;
                }
                stream = contentResolver.openInputStream(uri);

                // Computes sample size.
                int inSampleSize = calculateInSampleSize(options, thumbnailSize, thumbnailSize);

                // As we don't care about an exact size, we keep what we get.
                options = new BitmapFactory.Options();
                options.inSampleSize = inSampleSize;
                options.inPurgeable = true;
                Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);

                // Don't forget to close the stream.
                stream.close();

                if (isCancelled()) {
                    return null;
                }

                if (rotate != 0) {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(rotate);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                }

                if (isCancelled()) {
                    return null;
                }

                int dot = filePath.lastIndexOf(".");
                String fileExtension = filePath.substring(dot + 1);
                OutputStream outStream = new FileOutputStream(newFile);
                if (fileExtension.equals(pngExtension)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, quality, outStream);
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream);
                }
                outStream.flush();
                outStream.close();

                return bitmap;
            } catch (FileNotFoundException fnfe) {
                Log.e(LogTag, "File not found", fnfe);
                this.logStuff(fnfe.getMessage());
                return null;
            } catch (IOException ioe) {
                String errorMessage = ioe.getMessage();
                Log.e(LogTag, "IO Exception", ioe);
                this.logStuff(errorMessage);
                return null;
            }
        }

        private void logStuff(String message) {
            String[] splittedMessage = message.split(" ");
            String fullMessage = "";
            for (int i = 0; i < splittedMessage.length; i++) {
                fullMessage += splittedMessage[i] + " ";
            }
            Log.e(LogTag, fullMessage);
        }

        private void setInvisible() {
            // Log.d("COLLAGE", "Setting something invisible...");
            if (imageViewReference != null) {
                final ImageView imageView = imageViewReference.get();
                BitmapFetcherTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
                if (this == bitmapDownloaderTask) {
                    imageView.setVisibility(View.GONE);
                    imageView.setClickable(false);
                    imageView.setEnabled(false);
                }
            }
        }

        /**
         * Once the image is downloaded, associates it to the imageView
         */
        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (isCancelled()) {
                bitmap = null;
            }
            addBitmapToCache(position, bitmap);
            if (imageViewReference != null) {
                ImageView imageView = imageViewReference.get();
                BitmapFetcherTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
                if (this == bitmapDownloaderTask) {
                    imageView.setImageBitmap(bitmap);
                    Animation anim = AnimationUtils.loadAnimation(imageView.getContext(), android.R.anim.fade_in);
                    imageView.setAnimation(anim);
                    anim.start();
                }
            } else {
                setInvisible();
            }
        }
    }

    /**
     * A fake Drawable that will be attached to the imageView while the download
     * is in progress.
     * <p/>
     * <p>
     * Contains a reference to the actual download task, so that a download task
     * can be stopped if a new binding is required, and makes sure that only the
     * last started download process can bind its result, independently of the
     * download finish order.
     * </p>
     */
    static class DownloadedDrawable extends ColorDrawable {
        private final WeakReference<BitmapFetcherTask> bitmapDownloaderTaskReference;
        private long origId;

        public DownloadedDrawable(Context mContext, BitmapFetcherTask bitmapDownloaderTask, long origId) {
            super(defaultColors[random.nextInt(3)]);
            bitmapDownloaderTaskReference = new WeakReference<BitmapFetcherTask>(bitmapDownloaderTask);
            this.origId = origId;
        }

        public long getOrigId() {
            return origId;
        }

        public BitmapFetcherTask getBitmapDownloaderTask() {
            return bitmapDownloaderTaskReference.get();
        }
    }

    /*
     * Cache-related fields and methods.
     * 
     * We use a hard and a soft cache. A soft reference cache is too aggressively cleared by the
     * Garbage Collector.
     */

    private static final int HARD_CACHE_CAPACITY = 100;
    private static final int DELAY_BEFORE_PURGE = 10 * 1000; // in milliseconds

    // Hard cache, with a fixed maximum capacity and a life duration
    private final HashMap<Integer, Bitmap> sHardBitmapCache = new LinkedHashMap<Integer, Bitmap>(
            HARD_CACHE_CAPACITY / 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(LinkedHashMap.Entry<Integer, Bitmap> eldest) {
            if (size() > HARD_CACHE_CAPACITY) {
                // Entries push-out of hard reference cache are transferred to
                // soft reference cache
                sSoftBitmapCache.put(eldest.getKey(), new SoftReference<Bitmap>(eldest.getValue()));
                return true;
            } else
                return false;
        }
    };

    // Soft cache for bitmaps kicked out of hard cache
    private final static ConcurrentHashMap<Integer, SoftReference<Bitmap>> sSoftBitmapCache = new ConcurrentHashMap<Integer, SoftReference<Bitmap>>(
            HARD_CACHE_CAPACITY / 2);

    private final Handler purgeHandler = new Handler();

    private final Runnable purger = new Runnable() {
        public void run() {
            clearCache();
        }
    };

    /**
     * Adds this bitmap to the cache.
     *
     * @param bitmap The newly downloaded bitmap.
     */
    private void addBitmapToCache(Integer position, Bitmap bitmap) {
        if (bitmap != null) {
            synchronized (sHardBitmapCache) {
                sHardBitmapCache.put(position, bitmap);
            }
        }
    }

    /**
     * @param position The URL of the image that will be retrieved from the cache.
     * @return The cached bitmap or null if it was not found.
     */
    private Bitmap getBitmapFromCache(Integer position) {
        // First try the hard reference cache
        synchronized (sHardBitmapCache) {
            final Bitmap bitmap = sHardBitmapCache.get(position);
            if (bitmap != null) {
                // Log.d("CACHE ****** ", "Hard hit!");
                // Bitmap found in hard cache
                // Move element to first position, so that it is removed last
                return bitmap;
            }
        }

        // Then try the soft reference cache
        SoftReference<Bitmap> bitmapReference = sSoftBitmapCache.get(position);
        if (bitmapReference != null) {
            final Bitmap bitmap = bitmapReference.get();
            if (bitmap != null) {
                // Bitmap found in soft cache
                // Log.d("CACHE ****** ", "Soft hit!");
                return bitmap;
            } else {
                // Soft reference has been Garbage Collected
                sSoftBitmapCache.remove(position);
            }
        }

        return null;
    }

    /**
     * Clears the image cache used internally to improve performance. Note that
     * for memory efficiency reasons, the cache will automatically be cleared
     * after a certain inactivity delay.
     */
    public void clearCache() {
        sHardBitmapCache.clear();
        sSoftBitmapCache.clear();
    }

    /**
     * Allow a new delay before the automatic cache clear is done.
     */
    private void resetPurgeTimer() {
        // purgeHandler.removeCallbacks(purger);
        // purgeHandler.postDelayed(purger, DELAY_BEFORE_PURGE);
    }
}