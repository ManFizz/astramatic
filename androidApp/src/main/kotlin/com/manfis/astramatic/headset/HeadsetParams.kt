package com.manfis.astramatic.headset

import android.content.Context
import com.google.cardboard.proto.CardboardDevice.DeviceParams
import com.google.cardboard.sdk.qrcode.CardboardParamsUtils

/**
 * Параметры шлема для тех, у кого нет QR-кода. Сохраняются туда же, куда их кладёт QR-сканер SDK,
 * поэтому плеер подхватывает их без изменений в коде SDK. Длины в миллиметрах, углы в градусах.
 */
data class HeadsetParams(
    val interLensMm: Float,
    val screenToLensMm: Float,
    val fovDeg: Float,
    val k1: Float,
    val k2: Float,
    val alignment: Alignment,
    val trayToLensMm: Float,
) {
    enum class Alignment { Bottom, Center, Top }

    fun save(context: Context): Boolean {
        val proto = DeviceParams.newBuilder()
            .setVendor("AstraMatic")
            .setModel("Свой шлем")
            .setInterLensDistance(interLensMm / 1000f)
            .setScreenToLensDistance(screenToLensMm / 1000f)
            // Порядок углов: влево, вправо, вниз, вверх.
            .addAllLeftEyeFieldOfViewAngles(List(4) { fovDeg })
            .addAllDistortionCoefficients(listOf(k1, k2))
            .setVerticalAlignment(
                when (alignment) {
                    Alignment.Bottom -> DeviceParams.VerticalAlignmentType.BOTTOM
                    Alignment.Center -> DeviceParams.VerticalAlignmentType.CENTER
                    Alignment.Top -> DeviceParams.VerticalAlignmentType.TOP
                }
            )
            .setTrayToLensDistance(trayToLensMm / 1000f)
            .setPrimaryButton(DeviceParams.ButtonType.TOUCH)
            .build()
        return CardboardParamsUtils.writeDeviceParams(proto.toByteArray(), context)
    }

    companion object {
        /** Значения Google Cardboard v1, их SDK использует, пока ничего не сохранено. */
        val CardboardV1 = HeadsetParams(
            interLensMm = 60f,
            screenToLensMm = 42f,
            fovDeg = 40f,
            k1 = 0.441f,
            k2 = 0.156f,
            alignment = Alignment.Bottom,
            trayToLensMm = 35f,
        )

        /** Сохранённые параметры (свои или из QR-кода), иначе Cardboard v1. */
        fun load(context: Context): HeadsetParams {
            val bytes = CardboardParamsUtils.readDeviceParams(context) ?: return CardboardV1
            val proto = runCatching { DeviceParams.parseFrom(bytes) }.getOrNull() ?: return CardboardV1
            return HeadsetParams(
                interLensMm = proto.interLensDistance * 1000f,
                screenToLensMm = proto.screenToLensDistance * 1000f,
                fovDeg = proto.leftEyeFieldOfViewAnglesList.maxOrNull() ?: CardboardV1.fovDeg,
                k1 = proto.distortionCoefficientsList.getOrNull(0) ?: 0f,
                k2 = proto.distortionCoefficientsList.getOrNull(1) ?: 0f,
                alignment = when (proto.verticalAlignment) {
                    DeviceParams.VerticalAlignmentType.CENTER -> Alignment.Center
                    DeviceParams.VerticalAlignmentType.TOP -> Alignment.Top
                    else -> Alignment.Bottom
                },
                trayToLensMm = proto.trayToLensDistance * 1000f,
            )
        }
    }
}
