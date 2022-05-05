
import 'dart:typed_data';

import 'package:camerax/camera_works.dart';
import 'package:camerax/src/enum/hdr_state.dart';
import 'package:camerax/src/enum/night_state.dart';
import 'package:flutter/material.dart';
import 'package:image_cropper/image_cropper.dart';
import 'package:image_gallery_saver/image_gallery_saver.dart';
class CameraPage extends StatefulWidget {
  @override
  _CameraPageState createState() => _CameraPageState();
}

class _CameraPageState extends State<CameraPage> {
   CameraController cameraController;
  var _lensType = CameraType.back;

   double zoom = 0.0;
  @override
  void initState() {
    super.initState();
    cameraController = CameraController(_lensType);
    start();
  }


   @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          CameraView(cameraController),


          Container(
            alignment: Alignment.topCenter,
            margin: EdgeInsets.only(top: 32.0),
            child: _buildHDRNightView(),
          ),
          Container(
            alignment: Alignment.bottomCenter,
            margin: EdgeInsets.only(bottom: 32.0),
            child: _buildControls(),
          ),
        ],
      ),
    );
  }

   Widget _buildHDRNightView() {
     return Row(
       mainAxisAlignment: MainAxisAlignment.spaceAround,
       crossAxisAlignment: CrossAxisAlignment.center,
       children: [
         ValueListenableBuilder(
           valueListenable: cameraController.hdrState,
           builder: (context, state, child) {
             return IconButton(
               iconSize: 16,
               icon: Icon( state == HDRState.enable? Icons.hdr_on: Icons.hdr_off, size: 32),
               padding: EdgeInsets.zero,
               color:   Colors.white,
               onPressed: () {
                startHdrMode();
               },
             );
           },
         ),

         ValueListenableBuilder(
           valueListenable: cameraController.nightState,
           builder: (context, state, child) {
             return IconButton(
               iconSize: 16,
               icon: Icon( Icons.nightlight_round, size: 32),
               padding: EdgeInsets.zero,
               color:   state ==  NightState.enable ? Colors.white:Colors.black,
               onPressed: () {
                 print(cameraController.nightState.value.toString()+"VFCKCKCKC");
                 startNightMode();
               },
             );
           },
         ),

       ],
     );
   }

  Widget _buildControls() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceAround,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [


        IconButton(
            iconSize: 16,
            icon: Icon(Icons.flip_camera_android, size: 32),
            color: Colors.white,
            padding: EdgeInsets.zero,
            onPressed: () {
              switchCamera(
                  _lensType.index == 0 ? CameraType.back : CameraType.front);
            }),
        IconButton(
          iconSize: 70,
          icon: Icon(Icons.circle, size: 70),
          color: Colors.white,
          padding: EdgeInsets.zero,
          onPressed: takePicture,
        ),
        ValueListenableBuilder(
          valueListenable: cameraController.torchState,
          builder: (context, state, child) {
            return IconButton(
              iconSize: 16,
              icon: Icon(Icons.bolt_outlined, size: 32),
              padding: EdgeInsets.zero,
              color: state == FlashState.off ? Colors.white : Colors.black,
              onPressed: () {
                if (state == FlashState.on) {
                  setFlash(FlashState.off);
                }else if(state ==  FlashState.off){
                  setFlash(FlashState.automatic);
                } else {
                  setFlash(FlashState.on);
                }
              },
            );
          },
        ),
      ],
    );
  }

  @override
  void dispose() {
    stop();
    super.dispose();
  }

  void start() async {
    try {
      await cameraController.startAsync(isNightMode: 0,isHdrMode: 0);
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
    } catch (e) {
      print(e.toString());
    }
  }

  void startNightMode() async {
    try {
      await cameraController.startAsync(isHdrMode: 0,isNightMode: (cameraController.nightState.value  == NightState.enable)? 0:1);
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
    } catch (e) {
      print(e.toString());
    }
  }

  void startHdrMode() async{
    try {
      await cameraController.startAsync(isHdrMode: cameraController.hdrState.value  == HDRState.enable? 0:1,isNightMode: 0);
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
    } catch (e) {
      print(e.toString());
    }
  }

  void stop() {
    try {
      cameraController.dispose();
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
    } catch (e) {
      print(e.toString());
    }
  }

  void setFlash(FlashState type) async {
    try {
      await cameraController.setFlash(type);
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
    } catch (e) {
      print(e.toString());
    }
  }

  void switchCamera(CameraType type) async {
    try {
      await cameraController.switchCameraLens(type);
      setState(() {
        _lensType = type;
      });
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
    } catch (e) {
      print(e.toString());
    }
  }

  void takePicture() async {
    try {
      final image = await cameraController.takePicture();
      final croppedFile = await ImageCropper().cropImage(
        sourcePath: image.path,
        aspectRatioPresets: [
          CropAspectRatioPreset.square,
          CropAspectRatioPreset.ratio3x2,
          CropAspectRatioPreset.original,
          CropAspectRatioPreset.ratio4x3,
          CropAspectRatioPreset.ratio16x9
        ],
        uiSettings: [
          AndroidUiSettings(
              toolbarTitle: 'Cropper',
              toolbarColor: Colors.deepOrange,
              toolbarWidgetColor: Colors.white,
              initAspectRatio: CropAspectRatioPreset.original,
              lockAspectRatio: false),
          IOSUiSettings(
            title: 'Cropper',
          ),
          /// this settings is required for Web
        ],
      );
      Uint8List bytes = await croppedFile.readAsBytes();
      var result = await ImageGallerySaver.saveImage(
          bytes,
          quality: 60,
          name: "new_mage.jpg"
      );
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(' ${result.filePath}'),
      ));
    } on CameraException catch (e) {
      _showErrorSnackBar(e.message ?? '');
      print(e.toString());
    }
  }

  void _showErrorSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message),
    ));
  }
}