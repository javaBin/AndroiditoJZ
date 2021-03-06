package no.java.schedule.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.estimote.sdk.Beacon;
import com.estimote.sdk.BeaconManager;
import com.estimote.sdk.Nearable;
import com.estimote.sdk.Region;
import com.estimote.sdk.repackaged.gson_v2_3_1.com.google.gson.Gson;
import com.google.samples.apps.iosched.map.MapFragment;
import com.google.samples.apps.iosched.provider.ScheduleContract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import no.java.schedule.io.model.BeaconQueue;
import no.java.schedule.io.model.JzBeaconRegion;
import no.java.schedule.io.model.JzRegionList;

public class EstimoteBeaconManager {
    private BeaconManager mBeaconManager;
    private ArrayList<Region> mRegionList;
    private JzRegionList mJzRegionList;
    private Context mContext;
    private String scanId;
    private static final String TAG = "EstimoteBeaconManager";
    private static final String RegionInfoJson = "regioninfo.json";
    private BeaconQueue mBeaconQueue;
    private ScheduledExecutorService  mScheduledExecutorService;
    public MapFragment mMapFragment;

    public EstimoteBeaconManager(Context context, MapFragment mapFragment) {
        mContext = context;
        mBeaconManager = new BeaconManager(context);
        initializeRegionAndUUIDs();
        mBeaconQueue = new BeaconQueue();
        mScheduledExecutorService = Executors.newScheduledThreadPool(1);
        mMapFragment = mapFragment;
    }

    private void initializeRegionAndUUIDs() {
        if (mRegionList == null) {
            mRegionList = new ArrayList<>();
        }

        // read json info
        try {

            // Attempt to fetch from web
            JzRegionList assetGson = parseJzRegionList(
                    JsonUtil.assetJSONFile(RegionInfoJson, mContext)
            );

            /*
            JzRegionList onlineGson = parseJzRegionList(
                    downloadOnlineConfig(new URL("\"https://java.no/android-app-resources/2016_regioninfo.json"))
            ); */

            mJzRegionList = assetGson;
            if (mJzRegionList != null) {
                for (int i = 0; i < mJzRegionList.getRegions().size(); i++) {
                    JzBeaconRegion beaconRegion = mJzRegionList.getRegions().get(i);
                    Region region = new Region(beaconRegion.getName(),
                            UUID.fromString(mJzRegionList.getUUID()),
                            beaconRegion.getMajor(),
                            null);
                    mRegionList.add(region);
                    storeMarkerToDatabase(beaconRegion);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private JzRegionList parseJzRegionList(String fileToJsonObj) {
            return new Gson().fromJson(fileToJsonObj, JzRegionList.class);
    }

    /**
     * Return the text document at url, or null if not found
     *
     * @param url to fetch
     * @return null or content of file.
     */
    private static String downloadOnlineConfig(URL url) {

        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setDoOutput(true);
            connection.connect();

            if (connection.getResponseCode() != 200) {
                return null;
            }

            InputStream in = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "utf-8"), 8);
            StringBuilder builder = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }

            in.close();
            return builder.toString();

        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private void storeMarkerToDatabase(JzBeaconRegion region) {
        ContentValues values = new ContentValues();
        values.put(ScheduleContract.MapMarkers.MARKER_ID, region.getName());
        values.put(ScheduleContract.MapMarkers.MARKER_TYPE, region.getType());
        values.put(ScheduleContract.MapMarkers.MARKER_LABEL, region.getDescription());
        values.put(ScheduleContract.MapMarkers.MARKER_FLOOR, region.getLevel());
        values.put(ScheduleContract.MapMarkers.MARKER_LATITUDE, region.getCoordinates().getLatitude());
        values.put(ScheduleContract.MapMarkers.MARKER_LONGITUDE, region.getCoordinates().getLongitude());

        Uri uri = mContext.getContentResolver()
                .insert(ScheduleContract.MapMarkers.buildMarkerUri(),
                        values);
    }

    public void initializeEstimoteBeaconManager(Context context) {
        if (mBeaconManager == null) {
            mBeaconManager = new BeaconManager(context);
        }
        // Should be invoked in #onCreate.
        mBeaconManager.setNearableListener(new BeaconManager.NearableListener() {
            @Override
            public void onNearablesDiscovered(List<Nearable> nearables) {
                Log.d(TAG, "Discovered nearables: " + nearables);
            }
        });
    }

    public void startEstimoteBeaconManager() {
        // Should be invoked in #onStart.
        mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
            @Override
            public void onServiceReady() {
                scanId = mBeaconManager.startNearableDiscovery();
            }
        });

        if(mScheduledExecutorService.isShutdown()) {
            mScheduledExecutorService = Executors.newScheduledThreadPool(1);
        }

        mScheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if(!mBeaconQueue.isEmpty()) {
                    mBeaconQueue.clearBeacons();
                }
            }
        },0, 15, TimeUnit.SECONDS);
    }

    public void stopRanging() {
        // invoked in #onPause
        for (Region region : mRegionList) {
            mBeaconManager.stopRanging(region);
        }

        mScheduledExecutorService.shutdown();
    }

    public void stopEstimoteBeaconManager() {
        // Should be invoked in #onStop.
        mBeaconManager.stopNearableDiscovery(scanId);
    }

    public void destroyEstimoteBeaconManager() {
        // When no longer needed. Should be invoked in #onDestroy.
        mBeaconManager.disconnect();
    }

    public void startMonitorEstimoteBeacons(Context context) {
        // called onResume
        if (mBeaconManager == null) {
            mBeaconManager = new BeaconManager(context);
        }

        mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {

            @Override
            public void onServiceReady() {

                for (Region region : mRegionList) {
                    mBeaconManager.startMonitoring(region);
                    mBeaconManager.startRanging(region);
                }

                mBeaconManager.setMonitoringListener(new BeaconManager.MonitoringListener() {
                    @Override
                    public void onEnteredRegion(Region region, List<Beacon> beacons) {
                        if (!beacons.isEmpty()) {
                        }
                    }

                    @Override
                    public void onExitedRegion(Region region) {

                    }

                });

                mBeaconManager.setRangingListener(new BeaconManager.RangingListener() {
                    @Override
                    public void onBeaconsDiscovered(Region region, List<Beacon> list) {
                        if (!list.isEmpty()) {
                            JzBeaconRegion beaconRegion = getRegion(region);
                            mBeaconQueue.insertBeaconInformation(beaconRegion,list);
                            BeaconQueue.BeaconInformationItem item = mBeaconQueue.getHighestRssiBeacon();
                            Beacon highestRssiBeacon = item.getmBeacon();
                            JzBeaconRegion regionLocation = item.getmRegion();
                            mMapFragment.placeMarkerLocationOnCurrentRegion(regionLocation.getCoordinates());
                        }
                    }
                });
            }
        });
    }

    private JzBeaconRegion getRegion(Region region) {
        for(JzBeaconRegion beaconRegion : mJzRegionList.getRegions()) {
            if(beaconRegion.getName().equals(region.getIdentifier())) {
                return beaconRegion;
            }
        }

        return null;
    }



}
