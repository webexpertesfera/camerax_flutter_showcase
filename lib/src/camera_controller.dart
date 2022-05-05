import 'dart:async';
import 'dart:collection';
import 'dart:ffi';
import 'dart:io';

import 'package:camerax/src/enum/hdr_state.dart';
import 'package:camerax/src/enum/night_state.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';

import 'enum/enum.dart';
import 'params/camera_args.dart';
import 'util.dart';

/// A camera controller.
abstract class CameraController {
  /// Arguments for [CameraView].
  ValueNotifier<CameraArgs> get args;

  /// Torch state of the camera.
  ValueNotifier<FlashState> get torchState;
  /// HDR state of the camera.
  ValueNotifier<HDRState> get hdrState;
  /// Night state of the camera.
  ValueNotifier<NightState> get nightState;

  /// Create a [CameraController].
  ///
  /// [facing] target facing used to select camera.
  ///
  /// [formats] the barcode formats for image analyzer.
  factory CameraController(CameraType facing) => _CameraController(facing);

  /// Start the camera asynchronously.
  Future<void> startAsync({int isHdrMode=0, int isNightMode =0 });

  /// Switch the torch's state.
  Future<void> setFlash(FlashState state);


  ///   Set Zoom Ratio
  Future<void> setZoomRatio(double ratio);
  ///   Set HDR
  Future<bool> setHDR(bool enable);

  ///   Set Night Mode
  Future<bool> setNightMode(bool enable);


  /// Get status has Front Camera in Device
  Future<bool> hasFrontCamera();


  /// Get status has HDR Camera in Device
  Future<bool> hasHdrCamera();

  /// Get status has Night Camera in Device
  Future<bool> hasNightCamera();

  /// Get status has Back Camera in Device
  Future<bool> hasBackCamera();

  /// Switch the camera type front or back
  Future<void> switchCameraLens(CameraType type);



  /// Take Picture, return File
  Future<File> takePicture();

  /// Release the resources of the camera.
  void dispose();
}

class _CameraController implements CameraController {
  static const MethodChannel method =
      MethodChannel('camerax/method');
  static const EventChannel event =
      EventChannel('camerax/event');

  static const undetermined = 0;
  static const authorized = 1;
  static const denied = 2;

  static int id;
  static StreamSubscription subscription;

  final CameraType facing;

  @override
  final ValueNotifier<CameraArgs> args;

  @override
  final ValueNotifier<FlashState> torchState;

  bool torchable;

  _CameraController(this.facing)
      : args = ValueNotifier(null),
        torchState = ValueNotifier(FlashState.automatic),
        nightState=ValueNotifier(NightState.disable),
        hdrState =ValueNotifier(HDRState.disable),
        torchable = false {
    // In case new instance before dispose.
    if (id != null) {
      stop();
    }
    id = hashCode;
    // Listen event handler.
    subscription =
        event.receiveBroadcastStream().listen((data) => handleEvent(data));
  }

  void handleEvent(Map<dynamic, dynamic> event) {
    final name = event['name'];
    final data = event['data'];
    switch (name) {
      case 'flashState':
        if (data != null) {
          final state = FlashState.values[data];
          torchState.value = state;
        }
        break;
      default:
        throw UnimplementedError();
    }
  }

  @override
  Future<void> startAsync({int isHdrMode = 0, int isNightMode = 0}) async {
    try {

      ensure('startAsync');
      // Check authorization state.
      var state = await method.invokeMethod('state');
      if (state == undetermined) {
        final result = await method.invokeMethod('requestPermission');
        state = result ? authorized : denied;
      }
      if (state != authorized) {
        throw PlatformException(code: 'NO ACCESS');
      }
      print(facing.index);
      // Start camera.

      HashMap<String, Object> arguments = new HashMap();
      arguments.putIfAbsent("cameraIndex", () => facing.index);
      arguments.putIfAbsent("isHdrMode", () => isHdrMode);
      arguments.putIfAbsent("isNightMode", () => isNightMode);
      final answer =
          await method.invokeMethod('start', arguments);
      final textureId = answer['textureId'];
      final size = toSize(answer['size']);

      args.value = CameraArgs(textureId, size);
      torchable = answer['hasFlash'];
      var hdrMode= answer['isHdrMode'];
      var nightMode= answer['isNightMode'];
     // print('NIGJTTTTT'+answer.toString());

        if(nightMode == 1 ){
          nightState.value= NightState.enable;
        }else if(nightMode == 0 ){
          nightState.value= NightState.disable;
        }
        if(hdrMode == 1){
          hdrState.value= HDRState.enable;
        }else if(hdrMode == 0){
          hdrState.value= HDRState.disable;
        }

     /* final isHdrCameraAvailable = await hasHdrCamera();
      final isNightCameraAvailable = await hasNightCamera();

      if(!isHdrCameraAvailable){
        hdrState.value= HDRState.notSupported;
      }
      if(!isNightCameraAvailable){
        nightState.value= NightState.notSupported;
      }*/
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }


  @override
  Future<void> setZoomRatio(double ratio)async {
    // TODO: implement setZoomRatio
    try {
      await method.invokeMethod('setZoomRatio', ratio * .35);
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
    // throw UnimplementedError();
  }

  @override
  Future setFlash(FlashState state) async {
    try {
      ensure('setFlash');
      if (!torchable) {
        return;
      }
      await method.invokeMethod('setFlash', state.name);
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  void dispose() {
    try {
      if (hashCode == id) {
        stop();
        subscription?.cancel();
        subscription = null;
        id = null;
      }
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  void stop() {
    try {
      method.invokeMethod('stop');
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  void ensure(String name) {
    final message =
        'CameraController.$name called after CameraController.dispose\n'
        'CameraController methods should not be used after calling dispose.';
    assert(hashCode == id, message);
  }

  @override
  Future<bool> hasBackCamera() async {
    try {
      return await method.invokeMethod('hasBackCamera');
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  Future<bool> hasFrontCamera() async {
    try {
      return await method.invokeMethod('hasFrontCamera');
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  Future<void> switchCameraLens(CameraType type) async {
    try {
      if (type == CameraType.front) {
        await method.invokeMethod('switchCamera', type.index);
        await setFlash(FlashState.off);
      } else {
        await method.invokeMethod('switchCamera', type.index);
      }
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  Future<File> takePicture() async {
    try {
      final path = await method.invokeMethod('takePicture');
      return File(path);
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  Future<bool> setHDR(bool enable) async{
    try {
      final isEnable = await method.invokeMethod('startHdr', enable);
      return isEnable;
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }

  }

  @override
  Future<bool> setNightMode(bool enable)async {
    try {
      await startAsync();

      final isEnable = await method.invokeMethod('startNightMode', enable);
      return isEnable;
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  ValueNotifier<HDRState>  hdrState ;

  @override
  final ValueNotifier<NightState> nightState;

  @override
  Future<bool> hasHdrCamera()  async{
    try {
      return await method.invokeMethod('hasHDR');
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }

  @override
  Future<bool> hasNightCamera() async{
    try {
      return await method.invokeMethod('hasNightMode');
    } on PlatformException catch (e) {
      throw e.toCameraException();
    }
  }
}
