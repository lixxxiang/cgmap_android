package com.cgwx.cgmapdemo

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cgwx.cgmap.CGFeature
import com.cgwx.cgmap.CGLatLng
import com.cgwx.cgmap.CGMapView
import com.cgwx.cgmap.CGOverlayLayer
import com.cgwx.cgmap.CGRasterLayer
import com.cgwx.cgmap.DrawProperties
import com.cgwx.cgmap.FillProperties
import com.cgwx.cgmap.VertexProperties
import com.cgwx.cgmap.china2023
import com.cgwx.cgmap.hunan1
import com.cgwx.cgmap.hunan2
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger

class MainActivity : ComponentActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<String>
    private val cgMapView = mutableStateOf<CGMapView?>(null)
    private var myLocation: CGLatLng? = CGLatLng(0.0, 0.0)
    private val myLocationState = mutableStateOf<CGLatLng?>(null)

    private var initFlag: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.addLogAdapter(AndroidLogAdapter())

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                cgMapView.value?.getLocation()
                cgMapView.value?.locationLiveData?.observe(this) { location ->
                    myLocation = location
                    Log.d("myLocation", location.toString())

                    if (initFlag) {
                        cgMapView.value?.flyTo(location, 18.0)
                        initFlag = false
                    }
                }
            }
        }

        storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("权限", "存储权限已授予")
            } else {
                Log.e("权限", "存储权限被拒绝")
            }
        }

        setContent {
            MainScreen(
                savedInstanceState,
                {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                cgMapView,
                {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        cgMapView.value?.onStart()
    }

    override fun onResume() {
        super.onResume()
        cgMapView.value?.onResume()
    }

    override fun onPause() {
        super.onPause()
        cgMapView.value?.onPause()
    }

    override fun onStop() {
        super.onStop()
        cgMapView.value?.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cgMapView.value?.onDestroy()
    }
}

@Composable
fun MainScreen(
    savedInstanceState: Bundle? = null,
    onRequestPermission: () -> Unit,
    cgMapView: MutableState<CGMapView?>,
    onRequestStoragePermission: () -> Unit
) {
    val calloutFlag = remember { mutableStateOf<Boolean>(true) }
    val panFlag = remember { mutableStateOf<Boolean>(false) }
    val vertexFlag = remember { mutableStateOf<Boolean>(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // 添加生命周期观察者
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    cgMapView.value?.onCreate(savedInstanceState)
                }
                Lifecycle.Event.ON_START -> {
                    cgMapView.value?.onStart()
                }
                Lifecycle.Event.ON_RESUME -> {
                    cgMapView.value?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    cgMapView.value?.onPause()
                }
                Lifecycle.Event.ON_STOP -> {
                    cgMapView.value?.onStop()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    cgMapView.value?.onDestroy()
                }
                Lifecycle.Event.ON_ANY -> {
                    // 处理其他生命周期事件
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 添加滑动条相关的状态
    val showSlider = remember { mutableStateOf(false) }
    val sliderValue = remember { mutableStateOf(0f) }

    LaunchedEffect(cgMapView.value, lifecycleOwner) {
        cgMapView.value?.downloadProgress?.observe(lifecycleOwner) { progress ->
            val percentage = (progress.current.toFloat() / progress.total * 100).toInt()
            Log.d(
                "下载进度", "总瓦片数: ${progress.total} 已下载: ${progress.current} 完成百分比: $percentage% 当前下载: zoom=${progress.currentZoom}, x=${progress.currentX}, y=${progress.currentY}".trimIndent()
            )
        }

        cgMapView.value?.target?.observe(lifecycleOwner) { target ->
            Logger.d(target)
        }
    }

    ConstraintLayout(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val (
            layer, addRasterLayer, calloutSwitch, removeRasterLayer, showRasterLayer,
            hideRasterLayer, addSpotButton, recallSpotButton, redoSpotButton, doneSpotButton, addExtraParams, draw) = createRefs()
        val (locate, myLocation) = createRefs()
        val (measure, measureAdd, measureRecall, measureRedo, measureDone) = createRefs()
        val (move, tempAdd, pan, offline, offlineDownload, stopDownload, addMBTiles, removeMBTiles, showMBTiles, hideMBTiles, deleteMBTiles) = createRefs()
        val (cameraControl, flyTo, longClick, overlay, addOverlay, manageOverlay) = createRefs()
        val (animation, addAnimation, manualAnimation, autoAnimation, progressBar) = createRefs()
        val (village, addBorder, removeBorder, showBorder, hideBorder) = createRefs()

        CGMapViewComposable(modifier = Modifier.fillMaxSize(), savedInstanceState = savedInstanceState, mapView = cgMapView)

        Text(text = "LAYERS:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(layer) {
                top.linkTo(parent.top)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = {
            cgMapView.value?.addRasterLayer(
                CGRasterLayer(
                    layerId = "my-raster-layer",
                    sourceId = "my-raster-source",
                    tileUrl = china2023,
                    scheme = "tms",
                    callout = calloutFlag.value
                )
            )
        },
            modifier = Modifier.constrainAs(addRasterLayer) {
                top.linkTo(parent.top)
                start.linkTo(layer.end)
            }) {
            Icon(Icons.Filled.Add, contentDescription = "Add Layer")
        }

        FilledIconButton(onClick = {
//            cgMapView.value?.addRasterLayer(
//                CGRasterLayer(
//                    layerId = "my-raster-layer",
//                    sourceId = "my-raster-source",
//                    tileUrl = amap,
//                    scheme = "xyz",
//                    callout = calloutFlag.value
//                )
//            )
            cgMapView.value?.removeRasterLayer(
                CGRasterLayer(
                    layerId = "my-raster-layer",
                    sourceId = "my-raster-source",
                )
            )
        },
            modifier = Modifier.constrainAs(removeRasterLayer) {
                top.linkTo(parent.top)
                start.linkTo(addRasterLayer.end)
            }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove Layer")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.showRasterLayer(
                CGRasterLayer(layerId = "my-raster-layer")
            )
        },
            modifier = Modifier.constrainAs(showRasterLayer) {
                top.linkTo(parent.top)
                start.linkTo(removeRasterLayer.end)
            }) {
            Icon(Icons.Filled.Visibility, contentDescription = "Show Layer")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.hideRasterLayer(
                CGRasterLayer(layerId = "my-raster-layer")
            )
        },
            modifier = Modifier.constrainAs(hideRasterLayer) {
                top.linkTo(parent.top)
                start.linkTo(showRasterLayer.end)
            }) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide Layer")
        }

        Text(text = "LOCATE:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(locate) {
                top.linkTo(layer.bottom)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = onRequestPermission,
            modifier = Modifier.constrainAs(myLocation) {
                top.linkTo(layer.bottom)
                start.linkTo(locate.end)
            }) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Locate")
        }

        val properties = DrawProperties(
            circleColor = "#FF0000",
            circleRadius = 10F,
            circleStrokeColor = "#0000FF",
            circleStrokeWidth = 4F,
            lineWidth = 10F,
            lineColor = "#00FF00",
            tempLineColor = "#F0F0F0",
            tempLineWidth = 5F,
            fillColor = "#000F0F",
            fillOutlineColor = "#000000",
            fillOpacity = 1F
        )

        Text(text = "DRAW:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(draw) {
                top.linkTo(locate.bottom)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = { cgMapView.value?.addSpot(properties) },
            modifier = Modifier.constrainAs(addSpotButton) {
                top.linkTo(locate.bottom)
                start.linkTo(draw.end)
            }) {
            Icon(Icons.Filled.Add, contentDescription = "Add Spot")
        }

        FilledIconButton(onClick = { cgMapView.value?.recallSpot() },
            modifier = Modifier.constrainAs(recallSpotButton) {
                top.linkTo(locate.bottom)
                start.linkTo(addSpotButton.end)
            }) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Recall Spot")
        }

        FilledIconButton(onClick = { cgMapView.value?.redoSpot() },
            modifier = Modifier.constrainAs(redoSpotButton) {
                top.linkTo(locate.bottom)
                start.linkTo(recallSpotButton.end)
            }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Redo")
        }

        var feature: CGFeature? = CGFeature()
        FilledIconButton(onClick = {
            val map = cgMapView.value?.doneSpot()
            feature = map
            Logger.d(map?.toGeoJSON())
        },
            modifier = Modifier.constrainAs(doneSpotButton) {
                top.linkTo(locate.bottom)
                start.linkTo(redoSpotButton.end)
            }) {
            Icon(Icons.Outlined.Done, contentDescription = "Done")
        }

        FilledIconButton(onClick = {
            val extraParams = mapOf(
                "creator" to "John Doe",
                "accuracy" to 0.98,
                "isVerified" to true,
                "comments" to null
            )
            Logger.d(feature!!.addExtra(extraParams).toGeoJSON())
        },
            modifier = Modifier.constrainAs(addExtraParams) {
                top.linkTo(locate.bottom)
                start.linkTo(doneSpotButton.end)
            }) {
            Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Add Extra")
        }

        Text(text = "MEASURE:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(measure) {
                top.linkTo(draw.bottom)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = { cgMapView.value?.measureAdd(properties) },
            modifier = Modifier.constrainAs(measureAdd) {
                top.linkTo(draw.bottom)
                start.linkTo(measure.end)
            }) {
            Icon(Icons.Filled.Add, contentDescription = "Measure Add")
        }

        FilledIconButton(onClick = { cgMapView.value?.measureRecall() },
            modifier = Modifier.constrainAs(measureRecall) {
                top.linkTo(draw.bottom)
                start.linkTo(measureAdd.end)
            }) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Measure Recall")
        }

        FilledIconButton(onClick = { cgMapView.value?.measureRedo() },
            modifier = Modifier.constrainAs(measureRedo) {
                top.linkTo(draw.bottom)
                start.linkTo(measureRecall.end)
            }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Measure Redo")
        }

        FilledIconButton(onClick = {
            val distance = cgMapView.value?.measureDone()
            Logger.d(distance)
        },
            modifier = Modifier.constrainAs(measureDone) {
                top.linkTo(draw.bottom)
                start.linkTo(measureRedo.end)
            }) {
            Icon(Icons.Outlined.Check, contentDescription = "Measure Done")
        }

        Text(text = "MOVE:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(move) {
                top.linkTo(measure.bottom)
                start.linkTo(parent.start)
            })

        val fillProperties = FillProperties(fillColor = "#00ffff", lineColor = "#FF00FF")
        val vertexProperties = VertexProperties(fillColor = "#FF00FF", midCircleColor = "#000000", circleColor = "#FF0000")
        FilledIconButton(onClick = { cgMapView.value?.tempPolygonAdd(fillProperties) },
            modifier = Modifier.constrainAs(tempAdd) {
                top.linkTo(measure.bottom)
                start.linkTo(move.end)
            }) {
            Icon(Icons.Filled.Add, contentDescription = "Temp Polygon Add")
        }

        Row(
            modifier = Modifier.constrainAs(pan) {
                top.linkTo(measure.bottom)
                start.linkTo(tempAdd.end)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pan", modifier = Modifier
                    .padding(16.dp, 0.dp)
            )
            Switch(
                checked = panFlag.value,
                onCheckedChange = { isChecked ->
                    panFlag.value = isChecked
                    cgMapView.value?.pan(isChecked, "source-2", "layer-2", fillProperties, listOf("polygon-4"), onUpdate = { feature ->
//                        Logger.d(feature.toGeoJSON())
                    })
                }
            )
            Text(
                "Vertex", modifier = Modifier
                    .padding(16.dp, 0.dp)
            )
            Switch(
                checked = vertexFlag.value,
                onCheckedChange = { isChecked ->
                    vertexFlag.value = isChecked
                    cgMapView.value?.vertex(isChecked, "source-1", "layer-1", vertexProperties, listOf("polygon-2"), onUpdate = { feature ->
//                        Logger.d(feature.toGeoJSON())
                    })
                }
            )
        }

        Text(text = "OFFLINE:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(offline) {
                top.linkTo(move.bottom)
                start.linkTo(parent.start)
            })

        var mbTilesFileName: String?
        var xyzDir: String? = ""

        FilledIconButton(onClick = {
            onRequestStoragePermission()
            val coordinates: List<List<List<Double>>> = listOf(
                listOf(
                    listOf(125.250, 43.914),
                    listOf(125.249, 43.818),
                    listOf(125.393, 43.813),
                    listOf(125.389, 43.914),
                    listOf(125.250, 43.914) // 闭合多边形
                )
            )
            xyzDir = System.currentTimeMillis().toString()
            /**
             * for test
             */
//            xyzDir = "1742451055278"

            cgMapView.value?.download(coordinates = coordinates, tileUrl = china2023, minZoom = 1, maxZoom = 15, tileScheme = "tms", savePath = xyzDir!!)
        },
            modifier = Modifier.constrainAs(offlineDownload) {
                top.linkTo(move.bottom)
                start.linkTo(offline.end)
            }) {
            Icon(Icons.Outlined.Download, contentDescription = "Offline Download")
        }

        FilledIconButton(onClick = {
            if (cgMapView.value?.isProcessing()!!) {
                // 正在处理中
                val status = cgMapView.value?.getProcessingStatus() // 获取具体状态（"下载中"或"转换中"）

                // 根据不同状态停止相应操作
                when (status) {
                    "下载中" -> cgMapView.value?.stopDownload()
                    "转换中" -> cgMapView.value?.stopConversion()
                }
            }
        },
            modifier = Modifier.constrainAs(stopDownload) {
                top.linkTo(move.bottom)
                start.linkTo(offlineDownload.end)
            }) {
            Icon(Icons.Outlined.StopCircle, contentDescription = "Stop Downloading")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.addRasterLayer(CGRasterLayer("mbtiles-layer", "mbtiles-source", "mbtiles://${getDefaultTilePath(context, xyzDir!!)}/$xyzDir.mbtiles", "tms", false))
        },
            modifier = Modifier.constrainAs(addMBTiles) {
                top.linkTo(move.bottom)
                start.linkTo(stopDownload.end)
            }) {
            Icon(Icons.Filled.Add, contentDescription = "Add PMTiles")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.removeRasterLayer(CGRasterLayer(layerId = "mbtiles-layer", sourceId = "mbtiles-source"))
        },
            modifier = Modifier.constrainAs(removeMBTiles) {
                top.linkTo(move.bottom)
                start.linkTo(addMBTiles.end)
            }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove PMTiles")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.showRasterLayer(CGRasterLayer(layerId = "mbtiles-layer"))
        },
            modifier = Modifier.constrainAs(showMBTiles) {
                top.linkTo(move.bottom)
                start.linkTo(removeMBTiles.end)
            }) {
            Icon(Icons.Filled.Visibility, contentDescription = "Show PMTiles")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.hideRasterLayer(CGRasterLayer(layerId = "mbtiles-layer"))
        },
            modifier = Modifier.constrainAs(hideMBTiles) {
                top.linkTo(move.bottom)
                start.linkTo(showMBTiles.end)
            }) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide PMTiles")
        }

        Text(text = "CAMERA CONTROL:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(cameraControl) {
                top.linkTo(offline.bottom)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = {
            val dest = CGLatLng(26.968026861520798, 112.37452139116624)
            val zoom = 14.0
            cgMapView.value?.flyTo(dest, zoom)
        },
            modifier = Modifier.constrainAs(flyTo) {
                top.linkTo(offline.bottom)
                start.linkTo(cameraControl.end)
            }) {
            Icon(Icons.Filled.NearMe, contentDescription = "FlyTo")
        }

        Text(text = "LONG CLICK:      ENABLED ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(longClick) {
                top.linkTo(cameraControl.bottom)
                start.linkTo(parent.start)
            })

        Text(text = "OVERLAY:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(overlay) {
                top.linkTo(longClick.bottom)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = {
            cgMapView.value?.addOverlays(
                listOf(
                    CGOverlayLayer(rasterLayer = CGRasterLayer(layerId = "layer3", sourceId = "source3", tileUrl = hunan2, scheme = "tms", callout = false), isVisible = true, order = 1),
                    CGOverlayLayer(rasterLayer = CGRasterLayer(layerId = "layer2", sourceId = "source2", tileUrl = hunan1, scheme = "tms", callout = false), isVisible = false, order = 2),
                    CGOverlayLayer(rasterLayer = CGRasterLayer(layerId = "layer1", sourceId = "source1", tileUrl = china2023, scheme = "tms", callout = false), isVisible = true, order = 0),
                )
            )
        },
            modifier = Modifier.constrainAs(addOverlay) {
                top.linkTo(longClick.bottom)
                start.linkTo(overlay.end)
            }) {
            Icon(Icons.Outlined.Add, contentDescription = "Add Overlay")
        }


        var showDialog by remember { mutableStateOf(false) }
        val overlays = remember { mutableStateListOf<CGOverlayLayer>() }

        FilledIconButton(onClick = {
            overlays.clear()
            overlays.addAll(cgMapView.value?.getOverlays() ?: emptyList())
            showDialog = true
        },
            modifier = Modifier.constrainAs(manageOverlay) {
                top.linkTo(longClick.bottom)
                start.linkTo(addOverlay.end)
            }) {
            Icon(Icons.Outlined.Layers, contentDescription = "Manage Overlay")
        }

        if (showDialog) {
            ManageOverlayDialog(
                overlays = overlays,
                onDismiss = { showDialog = false },
                onVisibilityChanged = { id, isVisible ->
                    val index = overlays.indexOfFirst { it.rasterLayer.layerId == id }
                    if (index != -1) {
                        overlays[index] = overlays[index].copy(isVisible = isVisible) // 触发 UI 更新
                    }
                    cgMapView.value?.setOverlayVisibility(id, isVisible)
                }
            )
        }

        Text(text = "ANIMATION:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(animation) {
                top.linkTo(overlay.bottom)
                start.linkTo(parent.start)
            })

        FilledIconButton(onClick = {
            cgMapView.value?.addOverlays(
                listOf(
                    CGOverlayLayer(rasterLayer = CGRasterLayer(layerId = "layer3", sourceId = "source3", tileUrl = hunan2, scheme = "tms", callout = false), isVisible = true, order = 2),
                    CGOverlayLayer(rasterLayer = CGRasterLayer(layerId = "layer2", sourceId = "source2", tileUrl = hunan1, scheme = "tms", callout = false), isVisible = true, order = 1),
                    CGOverlayLayer(rasterLayer = CGRasterLayer(layerId = "layer1", sourceId = "source1", tileUrl = china2023, scheme = "tms", callout = false), isVisible = true, order = 0),
                )
            )
        },
            modifier = Modifier.constrainAs(addAnimation) {
                top.linkTo(overlay.bottom)
                start.linkTo(animation.end)
            }) {
            Icon(Icons.Outlined.Add, contentDescription = "Add Animation")
        }

        FilledIconButton(onClick = {
            showSlider.value = !showSlider.value
        },
            modifier = Modifier.constrainAs(manualAnimation) {
                top.linkTo(overlay.bottom)
                start.linkTo(addAnimation.end)
            }) {
            Icon(Icons.Outlined.PanTool, contentDescription = "Manual Animation")
        }

        val imageLayers = listOf("layer1", "layer2", "layer3")
        val fadePrevious by remember { mutableStateOf(false) }

        FilledIconButton(onClick = {
            cgMapView.value?.startTransitionAnimation(imageLayers, fadePrevious, 5000)
        },
            modifier = Modifier.constrainAs(autoAnimation) {
                top.linkTo(overlay.bottom)
                start.linkTo(manualAnimation.end)
            }) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "Auto Animation")
        }

        // 添加底部滑动条
        AnimatedVisibility(
            visible = showSlider.value,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.constrainAs(progressBar) {
                bottom.linkTo(parent.bottom)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                TimeSlider(imageLayers, fadePrevious) { progress ->
                    cgMapView.value?.setTransitionProgress(progress, imageLayers, fadePrevious)
                }
            }
        }

        Text(text = "VILLAGE:  ", modifier = Modifier
            .padding(16.dp)
            .constrainAs(village) {
                top.linkTo(animation.bottom)
                start.linkTo(parent.start)
            })

        val tileUrl = "http://121.36.101.27:8080/geoserver/china/wms?" +
                "service=WMS&version=1.1.0&request=GetMap" +
                "&layers=china:china" +
                "&format=image/png" +
                "&transparent=true" +
                "&srs=EPSG:3857" +
                "&bbox={bbox-epsg-3857}" +
                "&width=256&height=256"
        val border = CGRasterLayer(sourceId = "border-source", layerId = "border-layer", tileUrl = tileUrl)


        FilledIconButton(onClick = {
            cgMapView.value?.addBorder(border)
        },
            modifier = Modifier.constrainAs(addBorder) {
                top.linkTo(animation.bottom)
                start.linkTo(village.end)
            }) {
            Icon(Icons.Filled.Add, contentDescription = "Add Border")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.removeBorder(border)
        },
            modifier = Modifier.constrainAs(removeBorder) {
                top.linkTo(animation.bottom)
                start.linkTo(addBorder.end)
            }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove Border")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.showRasterLayer(border)
        },
            modifier = Modifier.constrainAs(showBorder) {
                top.linkTo(animation.bottom)
                start.linkTo(removeBorder.end)
            }) {
            Icon(Icons.Filled.Visibility, contentDescription = "Show Border")
        }

        FilledIconButton(onClick = {
            cgMapView.value?.hideRasterLayer(border)
        },
            modifier = Modifier.constrainAs(hideBorder) {
                top.linkTo(animation.bottom)
                start.linkTo(showBorder.end)
            }) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide Border")
        }
    }
}

@Composable
fun TimeSlider(imageLayers: List<String>, fadePrevious: Boolean, onProgressChange: (Float) -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    val maxIndex = (imageLayers.size - 1).toFloat()

    Column {
        Slider(
            value = progress,
            onValueChange = { newValue ->
                progress = newValue
                onProgressChange(newValue) // **实时更新**
            },
            valueRange = 0f..maxIndex, // `Slider` 允许浮点值
            steps = 0 // **不限制整数点，可以停在任意位置**
        )
    }
}


@Composable
fun CGMapViewComposable(
    modifier: Modifier = Modifier, savedInstanceState: Bundle?, mapView: MutableState<CGMapView?>,
) {

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Initialize your custom view
            CGMapView(ctx).apply {
                // Assume onCreate should be called similarly to this
                onCreate(savedInstanceState)
                // Additional setup can be done here if needed
            }

        },
        update = { m ->
            // Here you can update the mapView when Compose recomposes
            // and you need to reflect the updated state or data
            // For example, setting up listeners or updating the map data
            mapView.value = m

            /**
             * add Layer for test
             */
//            if (mapView.value != null) {
//                mapView.value?.addRasterLayer(
//                    CGRasterLayer(
//                        layerId = "my-raster-layer",
//                        sourceId = "my-raster-source",
//                        tileUrl = china2023,
//                        scheme = "tms",
//                        callout = true
//                    )
//                )
//            }

        }
    )
}

fun getDefaultTilePath(context: Context, subDir: String): String {
    return context.getExternalFilesDir(subDir)?.absolutePath ?: throw IllegalStateException("无法获取外部存储目录")
}

@Composable
fun ManageOverlayDialog(
    overlays: List<CGOverlayLayer>,
    onDismiss: () -> Unit,
    onVisibilityChanged: (String, Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "图层管理",
            )
        },
        text = {
            LazyColumn {
                val sortedOverlays = overlays.sortedBy { it.order }
                itemsIndexed(sortedOverlays) { index, overlay ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "${overlay.rasterLayer.layerId} order: ${overlay.order}")
                                Spacer(modifier = Modifier.weight(1f))
                                Switch(
                                    checked = overlay.isVisible,
                                    onCheckedChange = { isChecked ->
                                        onVisibilityChanged(overlay.rasterLayer.layerId, isChecked)
                                    }
                                )
                            }
                        }
                        if (index < sortedOverlays.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                thickness = 1.dp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}


