package cn.rainx.ptp.usbcamera;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import cn.rainx.ptp.detect.CameraDetector;

import cn.rainx.ptp.usbcamera.sony.SonyInitiator;

/**
 * Created by rainx on 2017/5/27.
 */

public class InitiatorFactory {
    public static final String TAG = InitiatorFactory.class.getName();

    static public BaselineInitiator produceInitiator(UsbDevice device, UsbManager usbManager) throws PTPException {
        BaselineInitiator bi;
        CameraDetector cd = new CameraDetector(device);
       if (cd.getSupportedVendorId() == CameraDetector.VENDOR_ID_SONY) {
            Log.d(TAG, "Device is Sony, open SonyInitiator");
            bi = new SonyInitiator(device, usbManager.openDevice(device));
        } else /* if (cd.getSupportedVendorId() == CameraDetector.VENDOR_ID_OTHER) */ {
            Log.d(TAG, "Unkown device, open BaselineInitiator");
            bi = new BaselineInitiator (device, usbManager.openDevice(device));
        }

        return bi;
    }
}
