/*
 * Copyright (C) 2015 University of South Florida, Sean J. Barbeau (sjbarbeau@gmail.com)
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
package org.onebusaway.android.map.googlemapsv2;

import com.amazon.geo.mapsv2.util.AmazonMapsRuntimeUtil;
import com.amazon.geo.mapsv2.util.ConnectionResult;

import android.app.Activity;
import android.content.Context;

/**
 * Helper methods specific to Amazon Maps API v2
 */
public class ProprietaryMapHelpV2 {

    public static boolean isMapsInstalled(Context context) {
        int resultCode = AmazonMapsRuntimeUtil
                .isAmazonMapsRuntimeAvailable(context);
        return resultCode == ConnectionResult.SUCCESS;
    }

    public static void promptUserInstallMaps(final Context context) {
        if (context instanceof Activity) {
            Activity a = (Activity) context;
            int resultCode = AmazonMapsRuntimeUtil
                    .isAmazonMapsRuntimeAvailable(context);
            AmazonMapsRuntimeUtil.getErrorDialog(resultCode, a, 0).show();
        }
    }
}
