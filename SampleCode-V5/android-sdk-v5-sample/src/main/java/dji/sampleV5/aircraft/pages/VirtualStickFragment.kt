package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragVirtualStickPageBinding
import dji.sampleV5.aircraft.keyvalue.KeyValueDialogUtil
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.LiveStreamVM
import dji.sampleV5.aircraft.models.SimulatorVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.interfaces.IKeyManager
import dji.v5.manager.interfaces.ISimulatorManager
import dji.v5.utils.common.JsonUtil
import dji.v5.utils.common.StringUtils
import java.util.Timer
import java.util.TimerTask
import kotlin.getValue
import kotlin.math.abs
import dji.sampleV5.aircraft.models.CameraStreamDetailVM
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType
import android.view.SurfaceHolder
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.LogPath
import android.view.Surface

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/5/11
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickFragment : DJIFragment(), CompoundButton.OnCheckedChangeListener {

    companion object {
        private const val KEY_CAMERA_INDEX = "cameraIndex"
        private const val KEY_ONLY_ONE_CAMERA = "onlyOneCamera"
        private val SUPPORT_YUV_FORMAT = mapOf(
            "YUV420（i420）" to ICameraStreamManager.FrameFormat.YUV420_888,
            "YUV444（i444）" to ICameraStreamManager.FrameFormat.YUV444_888,
            "NV21" to ICameraStreamManager.FrameFormat.NV21,
            "YUY2" to ICameraStreamManager.FrameFormat.YUY2,
            "RGBA" to ICameraStreamManager.FrameFormat.RGBA_8888
        )

        fun newInstance(cameraIndex: ComponentIndexType, onlyOneCamera: Boolean): CameraStreamDetailFragment {
            val args = Bundle()
            args.putInt(KEY_CAMERA_INDEX, cameraIndex.value())
            args.putBoolean(KEY_ONLY_ONE_CAMERA, onlyOneCamera)
            val fragment = CameraStreamDetailFragment()
            fragment.arguments = args
            return fragment
        }
    }

    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private val liveStreamVM: LiveStreamVM by viewModels()
    // 添加摄像头相关的变量
    private val cameraStreamDetailVM: CameraStreamDetailVM by viewModels()
    private var cameraSurface: Surface? = null
    private var surfaceWidth = -1
    private var surfaceHeight = -1
    private lateinit var cameraIndex: ComponentIndexType
    private var onlyOneCamera = false
    private var cameraScaleType = ICameraStreamManager.ScaleType.CENTER_INSIDE
    private var binding: FragVirtualStickPageBinding? = null
    private val deviation: Double = 0.02
    private var isZeroOneMode: Boolean = false // 是否开启01-慢速模式
    private var sendVirtualStickDataTimer: Timer? = null
    private var sendVirtualStickDataTask: TimerTask? = null
    private var pitch: Double = 0.0
    private var roll: Double = 0.0
    private var yaw: Double = 0.0
    private var verticalThrottle: Double = 0.0
    private var verticalControlMode: VerticalControlMode? = null
    private var rollPitchControlMode: RollPitchControlMode? = null
    private var yawControlMode: YawControlMode? = null

    private var rollPitchCoordinateSystem: FlightCoordinateSystem? = null
    private val emptyInputMessage = "input is empty"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraIndex = ComponentIndexType.find(arguments?.getInt(KEY_CAMERA_INDEX, 0) ?: 0)
        onlyOneCamera = arguments?.getBoolean(KEY_ONLY_ONE_CAMERA, false) ?: false
    }

    // 用于加载虚拟摇杆页面布局
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragVirtualStickPageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    // 在 Fragment 视图创建完成后，初始化 UI、设置监听器，并通过 LiveData 实时更新虚拟摇杆和模拟器的状态信息。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化UI配置
        binding?.widgetHorizontalSituationIndicator?.setSimpleModeEnable(false)

        // 设置监听器
        initBtnClickListener()
        initStickListener() // 虚拟摇杆监听
        initCameraStream() // 初始化摄像头流

        virtualStickVM.listenRCStick() // 遥控器摇杆监听

        // 观察各个属性的变化，用于更新属性信息
        virtualStickVM.currentSpeedLevel.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.useRcStick.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.stickValue.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.virtualStickAdvancedParam.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.text = it
        }
    }

    // 为所有的按钮设置监听
    private fun initBtnClickListener() {
        binding?.btnEnableVirtualStick?.setOnClickListener {
            virtualStickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("enableVirtualStick success.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("enableVirtualStick error,$error")
                }
            })
        }
        binding?.btnDisableVirtualStick?.setOnClickListener {
            virtualStickVM.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("disableVirtualStick success.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("disableVirtualStick error,${error})")
                }
            })
        }
        binding?.btnEnableStreaming?.setOnClickListener {
            showSetLiveStreamRtmpConfigDialog()
        }
        binding?.btnDisableStreaming?.setOnClickListener {
            stopLive()
        }
        binding?.btnSetVirtualStickSpeedLevel?.setOnClickListener {
            val speedLevels = doubleArrayOf(0.001, 0.01, 0.03, 0.05, 0.07, 0.09, 0.1, 0.2, 0.3)
            initPopupNumberPicker(Helper.makeList(speedLevels)) {
                virtualStickVM.setSpeedLevel(speedLevels[indexChosen[0]])
                resetIndex()
            }
        }
        binding?.btnTakeOff?.setOnClickListener {
            basicAircraftControlVM.startTakeOff(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start takeOff onSuccess.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start takeOff onFailure,$error")
                }
            })
        }
        binding?.btnLanding?.setOnClickListener {
            basicAircraftControlVM.startLanding(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start landing onSuccess.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start landing onFailure,$error")
                }
            })
        }
        binding?.btnUseRcStick?.setOnClickListener {
            virtualStickVM.useRcStick.value = virtualStickVM.useRcStick.value != true
            if (virtualStickVM.useRcStick.value == true) {
                ToastUtils.showToast(
                    "After it is turned on," +
                            "the joystick value of the RC will be used as the left/ right stick value"
                )
            }
        }
        binding?.btnSetVirtualStickAdvancedParam?.setOnClickListener {
            KeyValueDialogUtil.showInputDialog(
                activity, "Set Virtual Stick Advanced Param",
                JsonUtil.toJson(virtualStickVM.virtualStickAdvancedParam.value), "", false
            ) {
                it?.apply {
                    val param = JsonUtil.toBean(this, VirtualStickFlightControlParam::class.java)
                    if (param == null) {
                        ToastUtils.showToast("Value Parse Error")
                        return@showInputDialog
                    }
                    virtualStickVM.virtualStickAdvancedParam.postValue(param)
                }
            }
        }
        binding?.btnSendVirtualStickAdvancedParam?.setOnClickListener {
            virtualStickVM.virtualStickAdvancedParam.value?.let {
                virtualStickVM.sendVirtualStickAdvancedParam(it)
            }
        }
        binding?.btnEnableVirtualStickAdvancedMode?.setOnClickListener {
            virtualStickVM.enableVirtualStickAdvancedMode()
        }
        binding?.btnDisableVirtualStickAdvancedMode?.setOnClickListener {
            virtualStickVM.disableVirtualStickAdvancedMode()
        }

        // 01-慢速模式的监听
        binding?.btnZeroOneMode?.setOnClickListener {
            // 01摇杆 消息响应
            isZeroOneMode=true;
            ToastUtils.showToast("zero one success!");
        }

        // 开启/关闭仿真的监听
        binding?.btnStartSimulator?.setOnCheckedChangeListener(this)

        val isSimulatorOn = SimulatorManager.getInstance().isSimulatorEnabled()
        if (isSimulatorOn != null && isSimulatorOn) {
            binding?.btnStartSimulator?.setChecked(true)
        }
    }



    // 监听虚拟摇杆
    private fun initStickListener() {
        binding?.leftStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var leftPx = 0F
                var leftPy = 0F

//                if (abs(pX) >= deviation) {
//                    leftPx = pX
//                }
//
//                if (abs(pY) >= deviation) {
//                    leftPy = pY
//                }

                //对摇杆进行处理 要是只动了一点 就算不动
                if (abs(pX) < deviation) {
                    leftPx = 0F
                }

                if (abs(pY) < deviation) {
                    leftPy = 0F
                }

                // 当判断摇杆动了 直接将把数据设置为1 相当于拉满
                // 但是将速度设置 成非常低 而且有三个档位
                if(isZeroOneMode==true){
                    if (abs(pX) >= deviation) {
                        leftPx = 1F
                    }

                    if (abs(pY) >= deviation) {
                        leftPy = 1F
                    }
                } else {
                    if (abs(pX) >= deviation) {
                        leftPx = pX
                    }

                    if (abs(pY) >= deviation) {
                        leftPy = pY
                    }
                }

                virtualStickVM.setLeftPosition(
                    (leftPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (leftPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )

                if (null == sendVirtualStickDataTimer) {
                    sendVirtualStickDataTask = SendVirtualStickDataTask()
                    sendVirtualStickDataTimer = Timer()
                    sendVirtualStickDataTimer!!.schedule(sendVirtualStickDataTask, 0, 200)
                }
            }
        })

        binding?.rightStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var rightPx = 0F
                var rightPy = 0F

//                if (abs(pX) >= deviation) {
//                    rightPx = pX
//                }
//
//                if (abs(pY) >= deviation) {
//                    rightPy = pY
//                }

                //对摇杆进行处理 要是只动了一点 就算不动
                if (abs(pX) < deviation) {
                    rightPx = 0F
                }

                if (abs(pY) < deviation) {
                    rightPy = 0F
                }

                // 当判断摇杆动了 直接将把数据设置为1 相当于拉满
                // 但是将速度设置 成非常低 而且有三个档位
                if(isZeroOneMode==true) {
                    if (abs(pX) >= deviation) {
                        rightPx = 1F
                    }

                    if (abs(pY) >= deviation) {
                        rightPy = 1F
                    }
                } else {
                    if (abs(pX) >= deviation) {
                        rightPx = pX
                    }

                    if (abs(pY) >= deviation) {
                        rightPy = pY
                    }
                }

                virtualStickVM.setRightPosition(
                    (rightPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (rightPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )

                if (null == sendVirtualStickDataTimer) {
                    sendVirtualStickDataTask = SendVirtualStickDataTask()
                    sendVirtualStickDataTimer = Timer()
                    sendVirtualStickDataTimer!!.schedule(sendVirtualStickDataTask, 0, 200)
                }
            }
        })
    }
    // 初始化摄像头流
    private fun initCameraStream() {
        // 设置摄像头索引（使用默认摄像头）
        cameraStreamDetailVM.setCameraIndex(cameraIndex)

        // 设置 SurfaceView 的回调
        binding?.svCamera?.holder?.addCallback(cameraSurfaceCallback)

        // 观察摄像头状态
        cameraStreamDetailVM.cameraStreamEnableMap.observe(viewLifecycleOwner) { map ->
            map[cameraIndex]?.let { isEnabled ->
                // 可以在这里更新UI显示摄像头状态
                LogUtils.i(LogPath.SAMPLE, "Camera stream enabled: $isEnabled")
            }
        }

        // 自动开启摄像头流
        cameraStreamDetailVM.enableStream(true)
    }

    // 摄像头 Surface 回调
    private val cameraSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            cameraSurface = holder.surface
            updateCameraStream()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            updateCameraStream()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceWidth = 0
            surfaceHeight = 0
            cameraSurface = null
            updateCameraStream()
        }
    }

    // 更新摄像头流
    private fun updateCameraStream() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || cameraSurface == null) {
            cameraSurface?.let { surface ->
                cameraStreamDetailVM.removeCameraStreamSurface(surface)
            }
            return
        }

        cameraStreamDetailVM.putCameraStreamSurface(
            cameraSurface!!,
            surfaceWidth,
            surfaceHeight,
            cameraScaleType
        )
    }

    // 在 onDestroyView 中清理资源
    override fun onDestroyView() {
        super.onDestroyView()
        // 移除摄像头 Surface 并关闭流
        cameraSurface?.let { surface ->
            cameraStreamDetailVM.removeCameraStreamSurface(surface)
        }
        cameraStreamDetailVM.enableStream(false)
    }

    private fun updateVirtualStickInfo() {
        val builder = StringBuilder()
        builder.append("Speed level:").append(virtualStickVM.currentSpeedLevel.value)
        builder.append("\n")
        builder.append("Use rc stick as virtual stick:").append(virtualStickVM.useRcStick.value)
        builder.append("\n")
        builder.append("Is virtual stick enable:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable)
        builder.append("\n")
        builder.append("Current control permission owner:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.currentFlightControlAuthorityOwner)
        builder.append("\n")
        builder.append("Change reason:").append(virtualStickVM.currentVirtualStickStateInfo.value?.reason)
        builder.append("\n")
        builder.append("Rc stick value:").append(virtualStickVM.stickValue.value?.toString())
        builder.append("\n")
        builder.append("Is virtual stick advanced mode enable:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled)
        builder.append("\n")
        builder.append("Virtual stick advanced mode param:").append(virtualStickVM.virtualStickAdvancedParam.value?.toJson())
        builder.append("\n")
        mainHandler.post {
            binding?.virtualStickInfoTv?.text = builder.toString()
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        if (buttonView === binding?.btnStartSimulator) {
            onClickSimulator(isChecked)
        }
    }

    private fun onClickSimulator(isChecked: Boolean) {
        val simulator = SimulatorManager.getInstance() ?: return
        if (isChecked) {

            simulator.enableSimulator(
                InitializationSettings.createInstance(LocationCoordinate2D(23.0, 113.0), 10),
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        TODO("Not yet implemented")
                    }

                    override fun onFailure(p0: IDJIError) {
                        TODO("Not yet implemented")
                    }
                })
        } else {

            simulator.disableSimulator(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    TODO("Not yet implemented")
                }

                override fun onFailure(p0: IDJIError) {
                    TODO("Not yet implemented")
                }

            })
        }
    }

    private fun showSetLiveStreamRtmpConfigDialog() {
        val factory = LayoutInflater.from(requireContext())
        val rtmpConfigView = factory.inflate(R.layout.dialog_livestream_rtmp_config_view, null)
        val etRtmpUrl = rtmpConfigView.findViewById<EditText>(R.id.et_livestream_rtmp_config)
        etRtmpUrl.setText(
            liveStreamVM.getRtmpUrl().toCharArray(),
            0,
            liveStreamVM.getRtmpUrl().length
        )
        val configDialog = requireContext().let {
            AlertDialog.Builder(it, R.style.Base_ThemeOverlay_AppCompat_Dialog_Alert)
                .setIcon(android.R.drawable.ic_menu_camera)
                .setTitle(R.string.ad_set_live_stream_rtmp_config)
                .setCancelable(false)
                .setView(rtmpConfigView)
                .setPositiveButton(R.string.ad_confirm) { configDialog, _ ->
                    kotlin.run {
                        val inputValue = etRtmpUrl.text.toString()
                        if (TextUtils.isEmpty(inputValue)) {
                            ToastUtils.showToast(emptyInputMessage)
                        } else {
                            liveStreamVM.setRTMPConfig(inputValue)
                            startLive()
                        }
                        configDialog.dismiss()
                    }
                }
                .setNegativeButton(R.string.ad_cancel) { configDialog, _ ->
                    kotlin.run {
                        configDialog.dismiss()
                    }
                }
                .create()
        }
        configDialog.show()
    }

    private fun startLive() {
        if (!liveStreamVM.isStreaming()) {
            liveStreamVM.startStream(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showShortToast(StringUtils.getResStr(R.string.msg_start_live_stream_success))
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showLongToast(
                        StringUtils.getResStr(R.string.msg_start_live_stream_failed, error.description())
                    )
                }
            });
        }
    }

    private fun stopLive() {
        liveStreamVM.stopStream(null)
    }
    private inner class SendVirtualStickDataTask : TimerTask() {
        override fun run() {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(
                VirtualStickFlightControlParam(
                    pitch,
                    roll,
                    yaw,
                    verticalThrottle,
                    verticalControlMode,
                    rollPitchControlMode,
                    yawControlMode,
                    rollPitchCoordinateSystem
                )
            )
        }
    }

}