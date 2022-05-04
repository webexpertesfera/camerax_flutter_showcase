
import 'package:camerax/camera_works.dart';
import 'package:flutter/material.dart';

class CameraPage extends StatefulWidget {
  @override
  _CameraPageState createState() => _CameraPageState();
}

class _CameraPageState extends State<CameraPage> {
   CameraController cameraController;
  var _lensType = CameraType.back;


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
            alignment: Alignment.bottomCenter,
            margin: EdgeInsets.only(bottom: 32.0),
            child: _buildControls(),
          ),
        ],
      ),
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
                  setFlash(FlashState.on);
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
      await cameraController.startAsync();
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

      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(' ${image.path}'),
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