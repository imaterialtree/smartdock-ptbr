package cu.axel.smartdock.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.Utils

class SoundEventsReceiver : BroadcastReceiver() {
    override fun onReceive(p1: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> DeviceUtils.playEventSound(p1, "usb_sound")
            Intent.ACTION_POWER_CONNECTED -> {
                Utils.shouldPlayChargeComplete = true
                DeviceUtils.playEventSound(p1, "charge_sound")
            }
        }
    }
}
