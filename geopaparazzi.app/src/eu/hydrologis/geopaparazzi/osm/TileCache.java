/*
 * JGrass - Free Open Source Java GIS http://www.jgrass.org 
 * (C) HydroloGIS - www.hydrologis.com 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package eu.hydrologis.geopaparazzi.osm;

import static java.lang.Math.PI;
import static java.lang.Math.cos;
import static java.lang.Math.floor;
import static java.lang.Math.log;
import static java.lang.Math.tan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import eu.hydrologis.geopaparazzi.R;
import eu.hydrologis.geopaparazzi.util.ApplicationManager;

/**
 * A cache for osm tiles.
 * 
 * <p>
 * It also takes care to retrieve missing tiles from
 * web or disk, if they are not in cache.
 * </p>
 * 
 * @author Andrea Antonello (www.hydrologis.com)
 */
public class TileCache {
    /**
     * The log tag. 
     */
    private static final String LOGTAG = TileCache.class.getSimpleName();

    /**
     * The root folder for OSM tile storing. 
     */
    private File osmCacheDir = null;

    /**
     * The maximum number of tiles kept in memory.
     */
    public static final int imgCacheLimit = 27;

    /**
     * The caching {@link HashMap}.
     */
    private HashMap<String, Bitmap> tileCache = new HashMap<String, Bitmap>(27);

    /**
     * List of tiles that are currently being fectched and therefore 
     * should not be requested again.
     */
    private List<String> loadingTilesList = new ArrayList<String>();

    /**
     * The list of keys used to keep track of order of tiles.
     */
    private List<String> keyList = new ArrayList<String>(27);

    private Bitmap dummyTile;

    private ApplicationManager applicationManager;

    public TileCache( File cacheDir ) {
        applicationManager = ApplicationManager.getInstance();
        if (cacheDir == null) {
            cacheDir = applicationManager.getOsmCacheDir();
        }
        osmCacheDir = cacheDir;
    }

    /**
     * Puts a tile in cache, checking for cache size and limit.
     * 
     * @param key the tile's definition string.
     * @param tile the tile {@link Bitmap}.
     */
    public void put( String key, Bitmap tile ) {
        int size = tileCache.size();
        while( size > imgCacheLimit ) {
            // remove oldest tile
            String oldestKey = keyList.get(0);
            Bitmap removed = tileCache.remove(oldestKey);
            size = tileCache.size();
            if (removed != null) {
                removed.recycle();
                keyList.remove(0);
                // Log.v(LOGTAG, "Remove bitmap from cache (size = " + size + "): " + oldestKey);
            }
        }
        keyList.add(key);
        tileCache.put(key, tile);
    }
    /**
     * Gather a tile from the cache.
     * 
     * <p>
     * The sequence of actions are:
     * <ul>
     *  <li>check to see if the tile is in cache</li>
     *  <li>if tile not in cache check if the file exists</li>
     *  <li>if file doesn't exist, get it online</li>
     * </ul>
     * </p>
     * 
     * @param zoom the zoomlevel.
     * @param xtile the x index of the tile.
     * @param ytile the y index of the tile.
     * @return the tile or a dummy image if the tile could not be retrieved.
     * @throws IOException
     */
    public Bitmap get( int zoom, int xtile, int ytile ) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("/"); //$NON-NLS-1$
        sb.append(zoom);
        sb.append("/"); //$NON-NLS-1$
        sb.append(xtile);
        sb.append("/"); //$NON-NLS-1$
        final String folder = sb.toString();
        final String img = ytile + ".png"; //$NON-NLS-1$
        final String tileDef = folder + img;

        // check in cache
        Bitmap tileBitmap = tileCache.get(tileDef);
        if (tileBitmap != null) {
            // Log.v(LOGTAG, "Using image from cache: " + tileDef);
            return tileBitmap;
        }
        File tileFile = new File(osmCacheDir + tileDef);
        if (tileFile.exists()) {
            byte[] byteArrayForBitmap = new byte[512 * 512];
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inTempStorage = byteArrayForBitmap;
            tileBitmap = BitmapFactory.decodeFile(tileFile.getAbsolutePath(), opt);
            // BitmapDrawable bitmapDrawable = (BitmapDrawable) Drawable.createFromPath(tileFile
            // .getAbsolutePath());
            if (tileBitmap == null) {
                Log.e(LOGTAG, "Problems reading image from disk: " + tileFile); //$NON-NLS-1$
                tileBitmap = createDummyTile();
            } else {
                put(tileDef, tileBitmap);
                // Log.v(LOGTAG, "File bitmap in cache: " + tileDef);
            }
            return tileBitmap;
        } else {
            // try to get it online

            /*
             * first check if it is not already being fetched.
             * If it is being fetched, instead return the
             * dummy image, so that it will be loaded 
             * as soon as is on board.
             */
            boolean internetIsOn = applicationManager.isInternetOn();
            if (loadingTilesList.contains(tileDef)) {
                return createDummyTile();
            } else {
                if (internetIsOn)
                    loadingTilesList.add(tileDef);
            }

            /*
             * Preload a dummy tile and start a thread for 
             * fetching the tile.
             */
            tileBitmap = createDummyTile();
            if (internetIsOn) {
                new Thread(){
                    public void run() {
                        Bitmap tmpTileBitmap = null;
                        StringBuilder sb = new StringBuilder();
                        sb.append("http://tile.openstreetmap.org"); //$NON-NLS-1$
                        sb.append(tileDef);
                        String urlStr = sb.toString();
                        try {
                            // Log.v(LOGTAG, "Getting image from web: " + urlStr);
                            URL osmFetchUrl = new URL(urlStr);
                            InputStream tileInputStream = (InputStream) osmFetchUrl.getContent();
                            // BitmapDrawable bitmapDrawable = (BitmapDrawable) Drawable
                            // .createFromStream(tileInputStream, "src");
                            byte[] byteArrayForBitmap = new byte[512 * 512];
                            BitmapFactory.Options opt = new BitmapFactory.Options();
                            opt.inTempStorage = byteArrayForBitmap;
                            tmpTileBitmap = BitmapFactory.decodeStream(tileInputStream, null, opt);
                            if (tmpTileBitmap != null) {
                                put(tileDef, tmpTileBitmap);
                                // create folder if it doesn't exist
                                File folderFile = new File(osmCacheDir, folder);
                                folderFile.mkdirs();
                                File outFile = new File(folderFile, img);
                                dumpPicture(outFile, tmpTileBitmap);
                                // Log.v(LOGTAG, "Web bitmap in cache: " + tileDef);
                            }
                        } catch (Exception e) {
                            Log.e(LOGTAG, "Problems reading image from web: " + urlStr); //$NON-NLS-1$
                        }
                        // remove the tile from the being fetched list
                        loadingTilesList.remove(tileDef);
                    }
                }.start();
            }
            return tileBitmap;
        }
    }

    public void clear() {
        Collection<Bitmap> values = tileCache.values();
        for( Bitmap bitmap : values ) {
            bitmap.recycle();
        }
        tileCache.clear();
        // Log.d(LOGTAG, "Cleared tiles cache");
    }

    private Bitmap createDummyTile() {
        if (dummyTile == null) {
            dummyTile = BitmapFactory.decodeResource(applicationManager.getOsmView().getResources(), R.drawable.no_tile_256);
        }
        return dummyTile;
    }

    private void dumpPicture( File file, Bitmap bitmap ) throws IOException {
        FileOutputStream fOut = null;
        fOut = new FileOutputStream(file);
        // boolean didCompress =
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fOut);
        // Log.d(LOGTAG, "Compressed bitmap: " + file.getAbsolutePath() + " (successfull = " +
        // didCompress + ")");
        fOut.flush();
        fOut.close();
    }

    public static int[] latLon2ContainingTileNumber( final double lat, final double lon, final int zoom ) {
        int xtile = (int) floor((lon + 180) / 360 * (1 << zoom));
        int ytile = (int) floor((1 - log(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2 * (1 << zoom));
        // return ("" + zoom + "/" + xtile + "/" + ytile);
        return new int[]{xtile, ytile};
    }

    public static void fetchTiles( File cacheDir, double startLon, double startLat, double endLon, double endLat, int startZoom,
            int endZoom ) throws IOException {
        TileCache tC = new TileCache(cacheDir);
        for( int zoom = startZoom; zoom <= endZoom; zoom++ ) {
            for( double lon = startLon; lon <= endLon; lon++ ) {
                for( double lat = startLat; lat <= endLat; lat++ ) {
                    int[] xyTile = latLon2ContainingTileNumber(lat, lon, zoom);
                    tC.get(zoom, xyTile[0], xyTile[1]);
                }
            }
        }
    }

}