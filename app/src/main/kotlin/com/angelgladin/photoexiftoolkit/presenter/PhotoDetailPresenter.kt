package com.angelgladin.photoexiftoolkit.presenter

import android.content.Intent
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.angelgladin.photoexiftoolkit.common.BasePresenter
import com.angelgladin.photoexiftoolkit.common.BaseView
import com.angelgladin.photoexiftoolkit.data.GoogleMapsService
import com.angelgladin.photoexiftoolkit.data.domain.AddressResponse
import com.angelgladin.photoexiftoolkit.domain.ExifField
import com.angelgladin.photoexiftoolkit.domain.ExifTagsContainer
import com.angelgladin.photoexiftoolkit.domain.Location
import com.angelgladin.photoexiftoolkit.domain.Type
import com.angelgladin.photoexiftoolkit.extension.*
import com.angelgladin.photoexiftoolkit.util.Constants
import com.angelgladin.photoexiftoolkit.view.PhotoDetailView
import rx.Subscriber
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


/**
 * Created on 12/22/16.
 */
class PhotoDetailPresenter(override val view: PhotoDetailView) : BasePresenter<BaseView> {

    lateinit var exifTagsContainerList: List<ExifTagsContainer>
    lateinit var exifInterface: ExifInterface
    lateinit var filePath: String

    var latitude: Double? = null
    var longitude: Double? = null

    override fun initialize() {
    }

    fun getDataFromIntent(intent: Intent) {
        filePath = intent.getStringExtra(Constants.PATH_FILE_KEY)
        Log.d(this.javaClass.simpleName, filePath)

        updateExifTagsContainerList()

        val imageUri = Uri.fromFile(File(filePath))
        val file = File(filePath)
        view.setImage(file.name, file.getSize(), imageUri)

        view.setExifDataList(exifTagsContainerList)

        getAddressByTriggerRequest()
    }

    private fun getAddressByTriggerRequest() {
        view.showProgressDialog()
        if (latitude != null && longitude != null) {
            GoogleMapsService
                    .googleMapsApi
                    .getAddressObservable("$latitude,$longitude")
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(object : Subscriber<AddressResponse>() {
                        override fun onError(e: Throwable) {
                            Log.e(this.javaClass.simpleName, e.message)
                            view.onError("Something went wrong getting the address", e)
                            view.hideProgressDialog()
                        }

                        override fun onNext(t: AddressResponse) {
                            Log.d(this.javaClass.simpleName, t.resultList.first().formattedAddress)
                            view.showAddressOnRecyclerViewItem(t.resultList.first().formattedAddress)
                        }

                        override fun onCompleted() {
                            view.hideProgressDialog()
                        }
                    })
        }
    }

    private fun updateExifTagsContainerList() {
        exifInterface = ExifInterface(filePath)
        val map = exifInterface.getMap()
        exifTagsContainerList = transformList(map)

        latitude = map[Constants.EXIF_LATITUDE]?.toDouble()
        longitude = map[Constants.EXIF_LONGITUDE]?.toDouble()
    }

    private fun transformList(map: MutableMap<String, String>): List<ExifTagsContainer> {
        val locationsList = arrayListOf<ExifField>()
        val datesList = arrayListOf<ExifField>()
        val cameraPropertiesList = arrayListOf<ExifField>()
        val dimensionsList = arrayListOf<ExifField>()
        val othersList = arrayListOf<ExifField>()

        map.forEach {
            when {
                it.key == Constants.EXIF_LATITUDE
                        || it.key == Constants.EXIF_LONGITUDE ->
                    locationsList.add(ExifField(it.key, it.value))
                it.key == ExifInterface.TAG_DATETIME
                        || it.key == ExifInterface.TAG_GPS_DATESTAMP
                        || it.key == ExifInterface.TAG_DATETIME_DIGITIZED ->
                    datesList.add(ExifField(it.key, it.value))
                it.key == ExifInterface.TAG_MAKE
                        || it.key == ExifInterface.TAG_MODEL ->
                    cameraPropertiesList.add(ExifField(it.key, it.value))
                it.key == ExifInterface.TAG_IMAGE_LENGTH
                        || it.key == ExifInterface.TAG_IMAGE_WIDTH ->
                    dimensionsList.add(ExifField(it.key, it.value))
                else -> othersList.add(ExifField(it.key, it.value))
            }
        }
        return arrayListOf(ExifTagsContainer(locationsList, Type.LOCATION_DATA),
                ExifTagsContainer(datesList, Type.DATE),
                ExifTagsContainer(cameraPropertiesList, Type.CAMERA_PROPERTIES),
                ExifTagsContainer(dimensionsList, Type.DIMENSION),
                ExifTagsContainer(othersList, Type.OTHER))
    }

    fun onItemPressed(item: ExifTagsContainer) {
        view.showAlertDialogWhenItemIsPressed(item)
    }

    fun copyDataToClipboard(item: ExifTagsContainer) = view.copyDataToClipboard(item)

    fun editDate(item: ExifTagsContainer) {
        val year: Int
        val month: Int
        val day: Int
        if (item.list.isEmpty()) {
            val calendar = Calendar.getInstance()
            year = calendar.get(Calendar.YEAR)
            month = calendar.get(Calendar.MONTH)
            day = calendar.get(Calendar.DAY_OF_MONTH)
        } else {
            val date = item.list.first().attribute
            year = date.substring(0, 4).toInt()
            month = date.substring(5, 7).toInt() - 1
            day = date.substring(8, 10).toInt()
        }
        view.showDialogEditDate(year, month, day)
    }


    fun openDialogMap(item: ExifTagsContainer) {
        val latitude = item.list.find { it.tag == Constants.EXIF_LATITUDE }?.attribute?.toDouble()
        val longitude = item.list.find { it.tag == Constants.EXIF_LONGITUDE }?.attribute?.toDouble()
        view.openDialogMap(latitude, longitude)
    }

    fun shareData() {
        val s = exifTagsContainerList
                .map { "\n\n${it.type.name}:\n${it.getOnStringProperties()}" }.toString()
        view.shareData(s.substring(3, s.length - 1))
    }


    fun changeExifLocation(location: Location) {
        try {
            exifInterface.apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, exifInterface.getLatitudeRef(location.latitude))
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, exifInterface.convertDecimalToDegrees(location.latitude))
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, exifInterface.getLongitudeRef(location.longitude))
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, exifInterface.convertDecimalToDegrees(location.longitude))
            }
            exifInterface.saveAttributes()

            updateExifTagsContainerList()
            view.changeExifDataList(exifTagsContainerList)

            getAddressByTriggerRequest()
            view.onCompleteLocationChanged()
        } catch (e: IOException) {
            view.onError("Cannot change location data", e)
        }
    }

    fun changeExifDate(year: Int, month: Int, dayOfMonth: Int) {
        val locationExifContainerList = exifTagsContainerList.find { it.type == Type.DATE }?.list!!
        val dateTimeShort: String
        val dateTimeLong: String

        if (locationExifContainerList.isEmpty()) {
            val df = SimpleDateFormat("HH:mm:ss")
            val calendar = Calendar.getInstance()
            dateTimeLong = "$year:${appendZeroIfNeeded(month)}:${appendZeroIfNeeded(dayOfMonth)} ${df.format(calendar.time)}"
            dateTimeShort = ""
        } else {
            val auxListLong = mutableListOf<ExifField>()
            locationExifContainerList.forEach {
                if (it.attribute.length > 10) auxListLong.add(it)
            }
            val actualDate = auxListLong.first().attribute.substring(11)
            dateTimeLong = "$year:${appendZeroIfNeeded(month)}:${appendZeroIfNeeded(dayOfMonth)} $actualDate"
            dateTimeShort = "$year:${appendZeroIfNeeded(month)}:${appendZeroIfNeeded(dayOfMonth)}"
        }
        try {
            if (locationExifContainerList.isEmpty()) {
                exifInterface.setAttribute(ExifInterface.TAG_DATETIME, dateTimeLong)
            } else {
                locationExifContainerList.forEach {
                    if (it.attribute.length > 10)
                        exifInterface.setAttribute(it.tag, dateTimeLong)
                    else
                        exifInterface.setAttribute(it.tag, dateTimeShort)
                }
            }
            exifInterface.saveAttributes()

            updateExifTagsContainerList()
            view.changeExifDataList(exifTagsContainerList)

            view.onCompleteDateChanged()
        } catch (e: IOException) {
            view.onError("Cannot change date", e)
        }
    }

    private fun appendZeroIfNeeded(n: Int): String {
        val s = n.toString()
        if (s.length == 1)
            return "0$s"
        else
            return s
    }
}