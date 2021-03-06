package org.kde.kdeconnect.Helpers;

import android.content.Context;

public class NetworkHelper {

    public static boolean isOnMobileNetwork(Context context) {

        return false;

        /*
        if (context == null) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            return false; //No good way to know it
        }
        try {
            boolean mobile = false;
            final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network[] networks = connMgr.getAllNetworks();
            for (Network network : networks) {
                NetworkInfo info = connMgr.getNetworkInfo(network);
                if (info == null) {
                    continue;
                }
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                    mobile = info.isConnected();
                    continue;
                }
                //Log.e(info.getTypeName(),""+info.isAvailable());
                if (info.isAvailable())
                    return false; //We are connected to at least one non-mobile network
            }
            if (mobile) { //We suspect we are on a mobile net
                try (LineNumberReader is = new LineNumberReader(new FileReader("/proc/net/arp"))) {
                    //Check the number of network neighbours, on data it should be 0
                    is.skip(Long.MAX_VALUE);
                    //Log.e("NetworkHelper", "procnetarp has " + is.getLineNumber() + " lines");
                    if (is.getLineNumber() > 1) { //The first line are the headers
                        return false; //I have neighbours, so this doesn't look like a mobile network
                    }
                } catch (Exception e) {
                    Log.e("NetworkHelper", "Exception reading procnetarp", e);
                }
            }
            return mobile;
        } catch (Exception e) {
            Log.e("isOnMobileNetwork", "Something went wrong, but this is non-critical.", e);
        }
        return false;

        */
    }

}
