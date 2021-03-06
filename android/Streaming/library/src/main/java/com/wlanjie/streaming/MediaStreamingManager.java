package com.wlanjie.streaming;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.text.TextUtils;

import com.wlanjie.streaming.audio.AudioEncoder;
import com.wlanjie.streaming.audio.AudioProcessor;
import com.wlanjie.streaming.audio.AudioUtils;
import com.wlanjie.streaming.audio.FdkAACEncoder;
import com.wlanjie.streaming.audio.OnAudioEncoderListener;
import com.wlanjie.streaming.audio.OnAudioRecordListener;
import com.wlanjie.streaming.camera.Camera1;
import com.wlanjie.streaming.camera.Camera2;
import com.wlanjie.streaming.camera.CameraCallback;
import com.wlanjie.streaming.camera.LivingCamera;
import com.wlanjie.streaming.rtmp.Rtmp;
import com.wlanjie.streaming.setting.AudioSetting;
import com.wlanjie.streaming.setting.CameraSetting;
import com.wlanjie.streaming.setting.EncoderType;
import com.wlanjie.streaming.setting.StreamingSetting;
import com.wlanjie.streaming.util.StreamingLog;
import com.wlanjie.streaming.util.VideoUtil;
import com.wlanjie.streaming.video.OnMediaCodecEncoderListener;
import com.wlanjie.streaming.video.OpenH264Encoder;
import com.wlanjie.streaming.video.SurfaceTextureCallback;
import com.wlanjie.streaming.video.VideoParameter;
import com.wlanjie.streaming.video.VideoRenderer;

import java.nio.ByteBuffer;

/**
 * Created by wlanjie on 2017/6/25.
 */
public class MediaStreamingManager {

  public final static int CAMERA_FACING_BACK = 0;
  public final static int CAMERA_FACING_FRONT = 1;
  private final GLSurfaceView mGLSurfaceView;
  private final VideoRenderer mVideoRenderer;
  private AudioSetting mAudioSetting;
  private CameraSetting mCameraSetting;
  private StreamingSetting mStreamingSetting;
  private AudioProcessor mAudioProcessor;
  private AudioEncoder mAudioEncoder;
  private CallbackBridge mCallbacks = new CallbackBridge();
  private LivingCamera mCamera;
  private long mPresentTimeUs;
  private volatile boolean mIsStartPublish = false;
  private CameraCallback mCameraCallback;

  public MediaStreamingManager(final GLSurfaceView glSurfaceView) {
    mGLSurfaceView = glSurfaceView;
    mVideoRenderer = new VideoRenderer(glSurfaceView.getContext());
    mVideoRenderer.getSurfaceTexture().setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
      @Override
      public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        glSurfaceView.requestRender();
      }
    });
  }

  public void setCameraCallback(CameraCallback callback) {
    mCameraCallback = callback;
  }

  public void setSurfaceTextureCallback(SurfaceTextureCallback callback) {
    mVideoRenderer.setSurfaceTextureCallback(callback);
  }

  public void prepare(CameraSetting cameraSetting, StreamingSetting streamingSetting, AudioSetting audioSetting) {
    if (StreamingLog.isOpen()) {
      Rtmp.enableLog();
    }

    int videoWidth = VideoUtil.getVideoSize(streamingSetting.getVideoWidth());
    int videoHeight = VideoUtil.getVideoSize(streamingSetting.getVideoHeight());
    streamingSetting.setVideoWidth(videoWidth);
    streamingSetting.setVideoHeight(videoHeight);
    mCameraSetting = cameraSetting;
    mStreamingSetting = streamingSetting;
    mAudioSetting = audioSetting;
    mVideoRenderer.setCameraSetting(cameraSetting);
    mVideoRenderer.setStreamingSetting(streamingSetting);
    mAudioProcessor = new AudioProcessor(AudioUtils.getAudioRecord(audioSetting), audioSetting);

    mGLSurfaceView.setEGLContextClientVersion(2);
    mGLSurfaceView.setRenderer(mVideoRenderer);
    mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

    cameraSetting.setPreviewWidth(mCameraSetting.getPreviewWidth());
    cameraSetting.setPreviewHeight(mCameraSetting.getPreviewHeight());
    cameraSetting.setSurfaceTexture(mVideoRenderer.getSurfaceTexture());
    if (Build.VERSION.SDK_INT < 21) {
      mCamera = new Camera1(mCallbacks, cameraSetting);
    } else {
      mCamera = new Camera2(mCallbacks, cameraSetting, mGLSurfaceView.getContext());
    }
  }

  /**
   * open camera
   */
  public void resume() {
    mCamera.setFacing(CAMERA_FACING_FRONT);
    mCamera.start();
  }

  /**
   * stop camera
   */
  public void pause() {
    mCamera.stop();
  }

  public void startStreaming() {
    if (mIsStartPublish) {
      return;
    }
    if (mStreamingSetting == null) {
      throw new IllegalArgumentException("StreamingSetting is null.");
    }
    if (TextUtils.isEmpty(mStreamingSetting.getRtmpUrl()) || !mStreamingSetting.getRtmpUrl().startsWith("rtmp://")) {
      throw new IllegalArgumentException("url must be rtmp://");
    }
    int result = Rtmp.connect(mStreamingSetting.getRtmpUrl());
    if (result != 0) {
      throw new RuntimeException("connect rtmp server error.");
    }
    Rtmp.startPublish();
    mVideoRenderer.startEncoder();
    mAudioProcessor.start();
    mPresentTimeUs = System.nanoTime();
    if (mStreamingSetting.getEncoderType() == EncoderType.SOFT) {
      createVideoParameter();
      OpenH264Encoder.openEncoder();

      FdkAACEncoder.openEncoder(mAudioSetting.getChannelCount(), mAudioSetting.getSampleRate(), mAudioSetting.getMaxBps() * 1000);
      mVideoRenderer.setOnFrameListener(new VideoRenderer.OnFrameListener() {
        @Override
        public void onFrame(byte[] rgba) {
          if (!mIsStartPublish) {
            return;
          }
          OpenH264Encoder.encode(rgba, System.nanoTime() / 1000 - mPresentTimeUs);
        }
      });
    } else {
      mVideoRenderer.setOnMediaCodecEncoderListener(new OnMediaCodecEncoderListener() {
        @Override
        public void onEncode(ByteBuffer buffer, MediaCodec.BufferInfo info) {
          if (!mIsStartPublish) {
            return;
          }
          buffer.position(info.offset);
          buffer.limit(info.offset + info.size);
          byte[] h264 = new byte[info.size];
          buffer.get(h264, 0, info.size);
          Rtmp.writeVideo(h264, (int) (System.nanoTime() - mPresentTimeUs));
        }
      });
    }

    mAudioProcessor.setOnAudioRecordListener(new OnAudioRecordListener() {
      @Override
      public void onAudioRecord(byte[] buffer, int size) {
        if (mStreamingSetting.getEncoderType() == EncoderType.SOFT) {
          if (!mIsStartPublish) {
            return;
          }
          FdkAACEncoder.encode(buffer, (int) (System.nanoTime() - mPresentTimeUs));
        } else {
          if (mAudioEncoder == null) {
            mAudioEncoder = new AudioEncoder();
            mAudioEncoder.start(mAudioSetting);
            mAudioEncoder.setOnAudioEncoderListener(new OnAudioEncoderListener() {
              @Override
              public void onAudioEncode(byte[] data, int size, long timeUs) {
                if (!mIsStartPublish) {
                  return;
                }
                Rtmp.writeAudio(data, (int) (System.nanoTime() - mPresentTimeUs), mAudioSetting.getSampleRate(), mAudioSetting.getChannelCount());
              }
            });
          }
          mAudioEncoder.offerEncoder(buffer);
        }
      }
    });

    mIsStartPublish = true;
  }

  public boolean isStartPublish() {
    return mIsStartPublish;
  }

  private void createVideoParameter() {
    VideoParameter videoParameter = new VideoParameter();
    videoParameter.setBitrate(512);
    videoParameter.setFrameRate(25);
    StreamingLog.d("soft video width = " + mStreamingSetting.getVideoWidth());
    StreamingLog.d("soft video height = " + mStreamingSetting.getVideoHeight());
    StreamingLog.d("soft frame width = " + mCameraSetting.getPreviewWidth());
    StreamingLog.d("soft frame height = " + mCameraSetting.getPreviewHeight());
    videoParameter.setVideoWidth(mStreamingSetting.getVideoWidth());
    videoParameter.setVideoHeight(mStreamingSetting.getVideoHeight());
    int previewWidth = mCameraSetting.getPreviewWidth();
    int previewHeight = mCameraSetting.getPreviewHeight();
    int cameraWidth;
    int cameraHeight;
    if(false) {
      // 横屏
      cameraWidth = Math.max(previewWidth, previewHeight);
      cameraHeight = Math.min(previewWidth, previewHeight);
    } else {
      cameraWidth = Math.min(previewWidth, previewHeight);
      cameraHeight = Math.max(previewWidth, previewHeight);
    }
    videoParameter.setFrameWidth(cameraWidth);
    videoParameter.setFrameHeight(cameraHeight);
    OpenH264Encoder.setVideoParameter(videoParameter);
  }

  public void stopStreaming() {
    if (!mIsStartPublish) {
      return;
    }
    if (mStreamingSetting.getEncoderType() == EncoderType.SOFT) {
      FdkAACEncoder.closeEncoder();
      OpenH264Encoder.closeEncoder();
    } else {
      mVideoRenderer.stopEncoder();
    }
    mAudioProcessor.stopEncode();
    mAudioProcessor.interrupt();
    Rtmp.destroy();
    mVideoRenderer.destroy();
    mIsStartPublish = false;
  }

  public void switchCamera() {
    mCamera.setFacing(mCamera.getFacing() == CAMERA_FACING_FRONT ? CAMERA_FACING_BACK : CAMERA_FACING_FRONT);
  }

  class CallbackBridge implements CameraCallback {

    @Override
    public void onCameraOpened(int previewWidth, int previewHeight) {
      if (mCameraCallback != null) {
        mCameraCallback.onCameraOpened(previewWidth, previewHeight);
      }
    }

    @Override
    public void onCameraClosed() {
      if (mCameraCallback != null) {
        mCameraCallback.onCameraClosed();
      }
    }

    @Override
    public void onPreviewFrame(byte[] data) {
      if (mCameraCallback != null) {
        mCameraCallback.onPreviewFrame(data);
      }
    }
  }

  static {
    System.loadLibrary("wlanjie");
  }
}
