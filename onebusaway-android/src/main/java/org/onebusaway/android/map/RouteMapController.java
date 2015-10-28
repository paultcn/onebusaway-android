/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
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
package org.onebusaway.android.map;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.request.ObaStopsForRouteRequest;
import org.onebusaway.android.io.request.ObaStopsForRouteResponse;
import org.onebusaway.android.io.request.ObaTripsForRouteRequest;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.map.googlemapsv2.BaseMapFragment;
import org.onebusaway.android.util.UIHelp;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RouteMapController implements MapModeController {

    private static final String TAG = "RouteMapController";

    private static final int ROUTES_LOADER = 5677;

    private static final int VEHICLES_LOADER = 5678;

    private final Callback mFragment;

    private String mRouteId;

    private boolean mZoomToRoute;

    private int mLineOverlayColor;

    private RoutePopup mRoutePopup;

    private int mShortAnimationDuration;

    // In lieu of using an actual LoaderManager, which isn't
    // available in SherlockMapActivity
    private Loader<ObaStopsForRouteResponse> mRouteLoader;

    private RouteLoaderListener mRouteLoaderListener;

    private Loader<ObaTripsForRouteResponse> mVehiclesLoader;

    private VehicleLoaderListener mVehicleLoaderListener;

    public RouteMapController(Callback callback) {
        mFragment = callback;
        mLineOverlayColor = mFragment.getActivity()
                .getResources()
                .getColor(R.color.route_line_color_default);
        mShortAnimationDuration = mFragment.getActivity().getResources().getInteger(
                android.R.integer.config_shortAnimTime);
        mRoutePopup = new RoutePopup();
        mRouteLoaderListener = new RouteLoaderListener();
        mVehicleLoaderListener = new VehicleLoaderListener();
    }

    @Override
    public void setState(Bundle args) {
        assert (args != null);
        String routeId = args.getString(MapParams.ROUTE_ID);
        mZoomToRoute = args.getBoolean(MapParams.ZOOM_TO_ROUTE, false);
        if (!routeId.equals(mRouteId)) {
            mRouteId = routeId;
            mRoutePopup.showLoading();
            mFragment.showProgress(true);
            //mFragment.getLoaderManager().restartLoader(ROUTES_LOADER, null, this);
            mRouteLoader = mRouteLoaderListener.onCreateLoader(ROUTES_LOADER, null);
            mRouteLoader.registerListener(0, mRouteLoaderListener);
            mRouteLoader.startLoading();

            mVehiclesLoader = mVehicleLoaderListener.onCreateLoader(VEHICLES_LOADER, null);
            mVehiclesLoader.registerListener(0, mVehicleLoaderListener);
            mVehiclesLoader.startLoading();
        } else {
            // We are returning to the route view with the route already set, so show the header
            mRoutePopup.show();
        }
    }

    @Override
    public String getMode() {
        return MapParams.MODE_ROUTE;
    }

    @Override
    public void destroy() {
        mRoutePopup.hide();
        mFragment.getMapView().removeRouteOverlay();
        mVehicleRefreshHandler.removeCallbacks(mVehicleRefresh);
        mFragment.getMapView().removeVehicleOverlay();
    }

    @Override
    public void onPause() {
        mVehicleRefreshHandler.removeCallbacks(mVehicleRefresh);
    }

    /**
     * This is called when fm.beginTransaction().hide() or fm.beginTransaction().show() is called
     *
     * @param hidden True if the fragment is now hidden, false if it is not visible.
     */
    @Override
    public void onHidden(boolean hidden) {
        // If the fragment is no longer visible, hide the route header - otherwise, show it
        if (hidden) {
            mRoutePopup.hide();
        } else {
            mRoutePopup.show();
        }
    }

    @Override
    public void onResume() {
        // Make sure we schedule a future update for vehicles
        mVehicleRefreshHandler.removeCallbacks(mVehicleRefresh);
        mVehicleRefreshHandler.postDelayed(mVehicleRefresh, VEHICLE_REFRESH_PERIOD);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putString(MapParams.ROUTE_ID, mRouteId);
        outState.putBoolean(MapParams.ZOOM_TO_ROUTE, mZoomToRoute);
    }

    @Override
    public void onLocation() {
        // Don't care
    }

    @Override
    public void onNoLocation() {
        // Don't care
    }

    @Override
    public void notifyMapChanged() {
        // Don't care
    }

    //
    // Map popup
    //
    private class RoutePopup {

        private final Activity mActivity;

        private final View mView;

        private final TextView mRouteShortName;

        private final TextView mRouteLongName;

        private final TextView mAgencyName;

        private final ProgressBar mProgressBar;

        // Prevents completely hiding vehicle markers at top of route
        private int VEHICLE_MARKER_PADDING;

        RoutePopup() {
            mActivity = mFragment.getActivity();
            float paddingDp =
                    mActivity.getResources().getDimension(R.dimen.map_route_vehicle_markers_padding)
                            / mActivity.getResources().getDisplayMetrics().density;
            VEHICLE_MARKER_PADDING = UIHelp.dpToPixels(mActivity, paddingDp);
            mView = mActivity.findViewById(R.id.route_info);
            mFragment.getMapView().setPadding(0, mView.getHeight() + VEHICLE_MARKER_PADDING, 0, 0);
            mRouteShortName = (TextView) mView.findViewById(R.id.short_name);
            mRouteLongName = (TextView) mView.findViewById(R.id.long_name);
            mAgencyName = (TextView) mView.findViewById(R.id.agency);
            mProgressBar = (ProgressBar) mView.findViewById(R.id.route_info_loading_spinner);

            // Make sure the cancel button is shown
            View cancel = mView.findViewById(R.id.cancel_route_mode);
            cancel.setVisibility(View.VISIBLE);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ObaMapView obaMapView = mFragment.getMapView();
                    // We want to preserve the current zoom and center.
                    Bundle bundle = new Bundle();
                    bundle.putFloat(MapParams.ZOOM, obaMapView.getZoomLevelAsFloat());
                    Location point = obaMapView.getMapCenterAsLocation();
                    bundle.putDouble(MapParams.CENTER_LAT, point.getLatitude());
                    bundle.putDouble(MapParams.CENTER_LON, point.getLongitude());
                    mFragment.setMapMode(MapParams.MODE_STOP, bundle);
                }
            });
        }

        void showLoading() {
            mFragment.getMapView().setPadding(0, mView.getHeight() + VEHICLE_MARKER_PADDING, 0, 0);
            UIHelp.hideViewWithoutAnimation(mRouteShortName);
            UIHelp.hideViewWithoutAnimation(mRouteLongName);
            UIHelp.showViewWithoutAnimation(mView);
            UIHelp.showViewWithoutAnimation(mProgressBar);
        }

        /**
         * Show the route header and populate it with the provided information
         * @param route route information to show in the header
         * @param agencyName agency name to show in the header
         */
        void show(ObaRoute route, String agencyName) {
            mRouteShortName.setText(UIHelp.getRouteDisplayName(route));
            mRouteLongName.setText(UIHelp.getRouteDescription(route));
            mAgencyName.setText(agencyName);
            show();
        }

        /**
         * Show the route header with the existing route information
         */
        void show() {
            UIHelp.hideViewWithAnimation(mProgressBar, mShortAnimationDuration);
            UIHelp.showViewWithAnimation(mRouteShortName, mShortAnimationDuration);
            UIHelp.showViewWithAnimation(mRouteLongName, mShortAnimationDuration);
            UIHelp.showViewWithAnimation(mView, mShortAnimationDuration);
            mFragment.getMapView().setPadding(0, mView.getHeight() + VEHICLE_MARKER_PADDING, 0, 0);
        }

        void hide() {
            mFragment.getMapView().setPadding(0, 0, 0, 0);
            UIHelp.hideViewWithAnimation(mView, mShortAnimationDuration);
        }
    }

    private static final long VEHICLE_REFRESH_PERIOD = TimeUnit.SECONDS.toMillis(10);

    private final Handler mVehicleRefreshHandler = new Handler();

    private final Runnable mVehicleRefresh = new Runnable() {
        public void run() {
            refresh();
        }
    };

    /**
     * Refresh vehicle data from the OBA server
     */
    private void refresh() {
        if (mVehiclesLoader != null) {
            mVehiclesLoader.onContentChanged();
        }
    }

    //
    // Loaders
    //

    private static class RoutesLoader extends AsyncTaskLoader<ObaStopsForRouteResponse> {

        private final String mRouteId;

        public RoutesLoader(Context context, String routeId) {
            super(context);
            mRouteId = routeId;
        }

        @Override
        public ObaStopsForRouteResponse loadInBackground() {
            if (Application.get().getCurrentRegion() == null &&
                    TextUtils.isEmpty(Application.get().getCustomApiUrl())) {
                //We don't have region info or manually entered API to know what server to contact
                Log.d(TAG, "Trying to load stops for route from server " +
                            "without OBA REST API endpoint, aborting...");
                return null;
            }
            //Make OBA REST API call to the server and return result
            return new ObaStopsForRouteRequest.Builder(getContext(), mRouteId)
                    .setIncludeShapes(true)
                    .build()
                    .call();
        }

        @Override
        public void deliverResult(ObaStopsForRouteResponse data) {
            //mResponse = data;
            super.deliverResult(data);
        }

        @Override
        public void onStartLoading() {
            forceLoad();
        }
    }

    class RouteLoaderListener implements LoaderManager.LoaderCallbacks<ObaStopsForRouteResponse>,
            Loader.OnLoadCompleteListener<ObaStopsForRouteResponse> {

        @Override
        public Loader<ObaStopsForRouteResponse> onCreateLoader(int id,
                Bundle args) {
            return new RoutesLoader(mFragment.getActivity(), mRouteId);
        }

        @Override
        public void onLoadFinished(Loader<ObaStopsForRouteResponse> loader,
                ObaStopsForRouteResponse response) {

            ObaMapView obaMapView = mFragment.getMapView();

            if (response.getCode() != ObaApi.OBA_OK) {
                BaseMapFragment.showMapError(mFragment.getActivity(), response);
                return;
            }

            ObaRoute route = response.getRoute(response.getRouteId());

            mRoutePopup.show(route, response.getAgency(route.getAgencyId()).getName());

            if (route.getColor() != null) {
                mLineOverlayColor = route.getColor();
            }

            obaMapView.setRouteOverlay(mLineOverlayColor, response.getShapes());

            // Set the stops for this route
            List<ObaStop> stops = response.getStops();
            mFragment.showStops(stops, response);
            mFragment.showProgress(false);

            if (mZoomToRoute) {
                obaMapView.zoomToRoute();
                mZoomToRoute = false;
            }
            //
            // wait to zoom till we have the right response
            obaMapView.postInvalidate();
        }

        @Override
        public void onLoaderReset(Loader<ObaStopsForRouteResponse> loader) {
            mFragment.getMapView().removeRouteOverlay();
            mFragment.getMapView().removeVehicleOverlay();
        }

        @Override
        public void onLoadComplete(Loader<ObaStopsForRouteResponse> loader,
                ObaStopsForRouteResponse response) {
            onLoadFinished(loader, response);
        }
    }

    private static class VehiclesLoader extends AsyncTaskLoader<ObaTripsForRouteResponse> {

        private final String mRouteId;

        public VehiclesLoader(Context context, String routeId) {
            super(context);
            mRouteId = routeId;
        }

        @Override
        public ObaTripsForRouteResponse loadInBackground() {
            if (Application.get().getCurrentRegion() == null &&
                    TextUtils.isEmpty(Application.get().getCustomApiUrl())) {
                //We don't have region info or manually entered API to know what server to contact
                Log.d(TAG, "Trying to load trips (vehicles) for route from server " +
                        "without OBA REST API endpoint, aborting...");
                return null;
            }
            //Make OBA REST API call to the server and return result
            return new ObaTripsForRouteRequest.Builder(getContext(), mRouteId)
                    .setIncludeStatus(true)
                    .build()
                    .call();
        }

        @Override
        public void deliverResult(ObaTripsForRouteResponse data) {
            super.deliverResult(data);
        }

        @Override
        public void onStartLoading() {
            forceLoad();
        }
    }

    class VehicleLoaderListener implements LoaderManager.LoaderCallbacks<ObaTripsForRouteResponse>,
            Loader.OnLoadCompleteListener<ObaTripsForRouteResponse> {

        HashSet<String> routes = new HashSet<>(1);

        @Override
        public Loader<ObaTripsForRouteResponse> onCreateLoader(int id,
                Bundle args) {
            return new VehiclesLoader(mFragment.getActivity(), mRouteId);
        }

        @Override
        public void onLoadFinished(Loader<ObaTripsForRouteResponse> loader,
                ObaTripsForRouteResponse response) {

            ObaMapView obaMapView = mFragment.getMapView();

            if (response.getCode() != ObaApi.OBA_OK) {
                BaseMapFragment.showMapError(mFragment.getActivity(), response);
                return;
            }

            routes.clear();
            routes.add(mRouteId);

            obaMapView.updateVehicles(routes, response);

            // Clear any pending refreshes
            mVehicleRefreshHandler.removeCallbacks(mVehicleRefresh);

            // Post an update
            mVehicleRefreshHandler.postDelayed(mVehicleRefresh, VEHICLE_REFRESH_PERIOD);
        }

        @Override
        public void onLoaderReset(Loader<ObaTripsForRouteResponse> loader) {
            mFragment.getMapView().removeVehicleOverlay();
        }

        @Override
        public void onLoadComplete(Loader<ObaTripsForRouteResponse> loader,
                ObaTripsForRouteResponse response) {
            onLoadFinished(loader, response);
        }
    }
}
