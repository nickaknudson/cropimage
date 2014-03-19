package com.nickaknudson.android.imagecrop;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

import com.nickaknudson.android.imagecrop.ImageCropView.ImageCropViewDelegate;

import eu.janmuller.android.simplecropimage.BitmapManager;
import eu.janmuller.android.simplecropimage.HighlightView;
import eu.janmuller.android.simplecropimage.R;
import eu.janmuller.android.simplecropimage.RotateBitmap;
import eu.janmuller.android.simplecropimage.Util;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * @author nick
 * 
 */
public class ImageCropFragment extends Fragment {

	final int IMAGE_MAX_SIZE = 1024;

	private static final String TAG = "CropImage";
	public static final String IMAGE_PATH = "image-path";
	public static final String SCALE = "scale";
	public static final String ORIENTATION_IN_DEGREES = "orientation_in_degrees";
	public static final String ASPECT_X = "aspectX";
	public static final String ASPECT_Y = "aspectY";
	public static final String OUTPUT_X = "outputX";
	public static final String OUTPUT_Y = "outputY";
	public static final String SCALE_UP_IF_NEEDED = "scaleUpIfNeeded";
	public static final String CIRCLE_CROP = "circleCrop";
	public static final String RETURN_DATA = "return-data";
	public static final String RETURN_DATA_AS_BITMAP = "data";
	public static final String ACTION_INLINE_DATA = "inline-data";

	// These are various options can be specified in the intent.
	private Bitmap.CompressFormat mOutputFormat = Bitmap.CompressFormat.JPEG;
	private Uri mSaveUri = null;
	private boolean mDoFaceDetection = true;
	private boolean mCircleCrop = false;
	private final Handler mHandler = new Handler();

	private int mAspectX;
	private int mAspectY;
	private int mOutputX;
	private int mOutputY;
	private boolean mScale;
	private ImageCropView mImageView;
	private ContentResolver mContentResolver;
	private Bitmap mBitmap;
	private String mImagePath;

	boolean mWaitingToPick; // Whether we are wait the user to pick a face.
	boolean mSaving; // Whether the "save" button is already clicked.
	HighlightView mCrop;
	
	private CountDownLatch viewCreatedLatch = new CountDownLatch(1);

	private ImageCropFragmentDelegate fragmentDelegate;

	// These options specifiy the output image size and whether we should
	// scale the output to fit it (or just crop it).
	private boolean mScaleUp = true;

	private final BitmapManager.ThreadSet mDecodingThreads = new BitmapManager.ThreadSet();

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		mContentResolver = getActivity().getContentResolver();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {

		View view = inflater.inflate(R.layout.image_crop, container, false);

		// requestWindowFeature(Window.FEATURE_NO_TITLE);
		// setContentView(R.layout.cropimage);

		mImageView = (ImageCropView) view.findViewById(R.id.image_crop_view);
		mImageView.setDelegate(new ImageCropViewDelegate() {
			
			@Override
			public void setWaitingToPick(boolean waiting) {
				mWaitingToPick = waiting;
			}
			
			@Override
			public void setHighlighView(HighlightView hv) {
				mCrop = hv;
			}
			
			@Override
			public boolean isWaitingToPick() {
				return mWaitingToPick;
			}
			
			@Override
			public boolean isSaving() {
				return mSaving;
			}
		});
		viewCreatedLatch.countDown();
		return view;
	}
	
	/**
	 * @param delegate
	 */
	public void setDelegate(ImageCropFragmentDelegate delegate) {
		
		fragmentDelegate = delegate;
		
		new Thread() {
			public void run() {
				
				try {
					viewCreatedLatch.await();
					mHandler.post(new Runnable() {
						@Override
						public void run() {
							setupImageCrop();
						}
					});
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}.start();
	}
	
	protected void setupImageCrop() {
		// showStorageToast(this);
		if (fragmentDelegate.shouldCircleCrop()) {
			if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
				mImageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
			}
			mCircleCrop = true;
			mAspectX = 1;
			mAspectY = 1;
		}
		mImagePath = fragmentDelegate.imagePath();
		mSaveUri = getImageUri(mImagePath);
		mBitmap = getBitmap(mImagePath);
		mAspectX = fragmentDelegate.aspectX();
		mAspectY = fragmentDelegate.aspectY();
		mOutputX = fragmentDelegate.getOutputX();
		mOutputY = fragmentDelegate.getOutputY();
		mScale = fragmentDelegate.getScale();
		mScaleUp = fragmentDelegate.getScaleIfNeeded();
		if (mBitmap == null) {
			Log.d(TAG, "finish!!!");
			fragmentDelegate.onFinish();
		}
		// Make UI fullscreen.
		// getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		/*
		 * findViewById(R.id.discard).setOnClickListener( new
		 * View.OnClickListener() { public void onClick(View v) {
		 * 
		 * setResult(RESULT_CANCELED); finish(); } });
		 * 
		 * findViewById(R.id.save).setOnClickListener( new
		 * View.OnClickListener() { public void onClick(View v) {
		 * 
		 * try { onSaveClicked(); } catch (Exception e) { finish(); } } });
		 * findViewById(R.id.rotateLeft).setOnClickListener( new
		 * View.OnClickListener() { public void onClick(View v) {
		 * 
		 * mBitmap = Util.rotateImage(mBitmap, -90); RotateBitmap rotateBitmap =
		 * new RotateBitmap(mBitmap);
		 * mImageView.setImageRotateBitmapResetBase(rotateBitmap, true);
		 * mRunFaceDetection.run(); } });
		 * 
		 * findViewById(R.id.rotateRight).setOnClickListener( new
		 * View.OnClickListener() { public void onClick(View v) {
		 * 
		 * mBitmap = Util.rotateImage(mBitmap, 90); RotateBitmap rotateBitmap =
		 * new RotateBitmap(mBitmap);
		 * mImageView.setImageRotateBitmapResetBase(rotateBitmap, true);
		 * mRunFaceDetection.run(); } });
		 */
		startFaceDetection();
	}

	protected void rotateLeft() {
		mBitmap = Util.rotateImage(mBitmap, -90);
		RotateBitmap rotateBitmap = new RotateBitmap(mBitmap);
		mImageView.setImageRotateBitmapResetBase(rotateBitmap, true);
		mRunFaceDetection.run();
	}

	protected void rotateRight() {
		mBitmap = Util.rotateImage(mBitmap, 90);
		RotateBitmap rotateBitmap = new RotateBitmap(mBitmap);
		mImageView.setImageRotateBitmapResetBase(rotateBitmap, true);
		mRunFaceDetection.run();
	}

	private Uri getImageUri(String path) {
		return Uri.fromFile(new File(path));
	}

	private Bitmap getBitmap(String path) {
		Uri uri = getImageUri(path);
		InputStream in = null;
		try {
			in = mContentResolver.openInputStream(uri);

			// Decode image size
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;

			BitmapFactory.decodeStream(in, null, o);
			in.close();

			int scale = 1;
			if (o.outHeight > IMAGE_MAX_SIZE || o.outWidth > IMAGE_MAX_SIZE) {
				scale = (int) Math.pow(
						2,
						(int) Math.round(Math.log(IMAGE_MAX_SIZE
								/ (double) Math.max(o.outHeight, o.outWidth))
								/ Math.log(0.5)));
			}

			BitmapFactory.Options o2 = new BitmapFactory.Options();
			o2.inSampleSize = scale;
			in = mContentResolver.openInputStream(uri);
			Bitmap b = BitmapFactory.decodeStream(in, null, o2);
			in.close();
			return b;
		} catch (FileNotFoundException e) {
			Log.e(TAG, "file " + path + " not found");
		} catch (IOException e) {
			Log.e(TAG, "file " + path + " not found");
		}
		return null;
	}

	private void startFaceDetection() {

		//if (isFinishing()) {
		//	return;
		//}

		mImageView.setImageBitmapResetBase(mBitmap, true);

		//mHandler.post(new Runnable() {
		new Thread() {
			public void run() {
				final CountDownLatch latch = new CountDownLatch(1);
				final Bitmap b = mBitmap;
				mHandler.post(new Runnable() {
					public void run() {
						if (b != mBitmap && b != null) {
							mImageView.setImageBitmapResetBase(b, true);
							mBitmap.recycle();
							mBitmap = b;
						}
						if (mImageView.getScale() == 1F) {
							mImageView.center(true, true);
						}
						latch.countDown();
					}
				});
				try {
					latch.await();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				mRunFaceDetection.run();
			}
		}.start();
	}

	private void onSaveClicked() throws Exception {
		// TODO this code needs to change to use the decode/crop/encode single
		// step api so that we don't require that the whole (possibly large)
		// bitmap doesn't have to be read into memory
		if (mSaving)
			return;

		if (mCrop == null) {

			return;
		}

		mSaving = true;

		Rect r = mCrop.getCropRect();

		int width = r.width();
		int height = r.height();

		// If we are circle cropping, we want alpha channel, which is the
		// third param here.
		Bitmap croppedImage;
		try {

			croppedImage = Bitmap.createBitmap(width, height,
					mCircleCrop ? Bitmap.Config.ARGB_8888
							: Bitmap.Config.RGB_565);
		} catch (Exception e) {
			throw e;
		}
		if (croppedImage == null) {

			return;
		}

		{
			Canvas canvas = new Canvas(croppedImage);
			Rect dstRect = new Rect(0, 0, width, height);
			canvas.drawBitmap(mBitmap, r, dstRect, null);
		}

		if (mCircleCrop) {

			// OK, so what's all this about?
			// Bitmaps are inherently rectangular but we want to return
			// something that's basically a circle. So we fill in the
			// area around the circle with alpha. Note the all important
			// PortDuff.Mode.CLEAR.
			Canvas c = new Canvas(croppedImage);
			Path p = new Path();
			p.addCircle(width / 2F, height / 2F, width / 2F, Path.Direction.CW);
			c.clipPath(p, Region.Op.DIFFERENCE);
			c.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
		}

		/* If the output is required to a specific size then scale or fill */
		if (mOutputX != 0 && mOutputY != 0) {

			if (mScale) {

				/* Scale the image to the required dimensions */
				Bitmap old = croppedImage;
				croppedImage = Util.transform(new Matrix(), croppedImage,
						mOutputX, mOutputY, mScaleUp);
				if (old != croppedImage) {

					old.recycle();
				}
			} else {

				/*
				 * Don't scale the image crop it to the size requested. Create
				 * an new image with the cropped image in the center and the
				 * extra space filled.
				 */

				// Don't scale the image but instead fill it so it's the
				// required dimension
				Bitmap b = Bitmap.createBitmap(mOutputX, mOutputY,
						Bitmap.Config.RGB_565);
				Canvas canvas = new Canvas(b);

				Rect srcRect = mCrop.getCropRect();
				Rect dstRect = new Rect(0, 0, mOutputX, mOutputY);

				int dx = (srcRect.width() - dstRect.width()) / 2;
				int dy = (srcRect.height() - dstRect.height()) / 2;

				/* If the srcRect is too big, use the center part of it. */
				srcRect.inset(Math.max(0, dx), Math.max(0, dy));

				/* If the dstRect is too big, use the center part of it. */
				dstRect.inset(Math.max(0, -dx), Math.max(0, -dy));

				/* Draw the cropped bitmap in the center */
				canvas.drawBitmap(mBitmap, srcRect, dstRect, null);

				/* Set the cropped bitmap as the new bitmap */
				croppedImage.recycle();
				croppedImage = b;
			}
		}

		final Bitmap b = croppedImage;
		/*
		 * Util.startBackgroundJob(this, null, getString(R.string.saving_image),
		 * new Runnable() { public void run() {saveOutput(b); } }, mHandler);
		 */
		mHandler.post(new Runnable() {

			@Override
			public void run() {
				saveOutput(b);
			}
		});
	}

	private void saveOutput(Bitmap croppedImage) {

		if (mSaveUri != null) {
			OutputStream outputStream = null;
			try {
				outputStream = mContentResolver.openOutputStream(mSaveUri);
				if (outputStream != null) {
					croppedImage.compress(mOutputFormat, 90, outputStream);
				}
			} catch (IOException ex) {

				Log.e(TAG, "Cannot open file: " + mSaveUri, ex);
				// setResult(RESULT_CANCELED);
				fragmentDelegate.onFinish();
				return;
			} finally {

				Util.closeSilently(outputStream);
			}

			Bundle extras = new Bundle();
			Intent intent = new Intent(mSaveUri.toString());
			intent.putExtras(extras);
			intent.putExtra(IMAGE_PATH, mImagePath);
			// intent.putExtra(ORIENTATION_IN_DEGREES,
			// Util.getOrientationInDegree(this));
			// setResult(RESULT_OK, intent);
		} else {

			Log.e(TAG, "not defined image url");
		}
		croppedImage.recycle();
		fragmentDelegate.onFinish();
	}

	@Override
	public void onPause() {

		super.onPause();
		BitmapManager.instance().cancelThreadDecoding(mDecodingThreads);
	}

	@Override
	public void onDestroy() {

		super.onDestroy();

		if (mBitmap != null) {

			mBitmap.recycle();
		}
	}

	Runnable mRunFaceDetection = new Runnable() {
		float mScale = 1F;
		Matrix mImageMatrix;
		FaceDetector.Face[] mFaces = new FaceDetector.Face[3];
		int mNumFaces;

		// For each face, we create a HightlightView for it.
		private void handleFace(FaceDetector.Face f) {

			PointF midPoint = new PointF();

			int r = ((int) (f.eyesDistance() * mScale)) * 2;
			f.getMidPoint(midPoint);
			midPoint.x *= mScale;
			midPoint.y *= mScale;

			int midX = (int) midPoint.x;
			int midY = (int) midPoint.y;

			HighlightView hv = new HighlightView(mImageView);

			int width = mBitmap.getWidth();
			int height = mBitmap.getHeight();

			Rect imageRect = new Rect(0, 0, width, height);

			RectF faceRect = new RectF(midX, midY, midX, midY);
			faceRect.inset(-r, -r);
			if (faceRect.left < 0) {
				faceRect.inset(-faceRect.left, -faceRect.left);
			}

			if (faceRect.top < 0) {
				faceRect.inset(-faceRect.top, -faceRect.top);
			}

			if (faceRect.right > imageRect.right) {
				faceRect.inset(faceRect.right - imageRect.right, faceRect.right
						- imageRect.right);
			}

			if (faceRect.bottom > imageRect.bottom) {
				faceRect.inset(faceRect.bottom - imageRect.bottom,
						faceRect.bottom - imageRect.bottom);
			}

			hv.setup(mImageMatrix, imageRect, faceRect, mCircleCrop,
					mAspectX != 0 && mAspectY != 0);

			mImageView.add(hv);
		}

		// Create a default HightlightView if we found no face in the picture.
		private void makeDefault() {

			HighlightView hv = new HighlightView(mImageView);

			int width = mBitmap.getWidth();
			int height = mBitmap.getHeight();

			Rect imageRect = new Rect(0, 0, width, height);

			// make the default size about 4/5 of the width or height
			int cropWidth = Math.min(width, height) * 4 / 5;
			int cropHeight = cropWidth;

			if (mAspectX != 0 && mAspectY != 0) {

				if (mAspectX > mAspectY) {

					cropHeight = cropWidth * mAspectY / mAspectX;
				} else {

					cropWidth = cropHeight * mAspectX / mAspectY;
				}
			}

			int x = (width - cropWidth) / 2;
			int y = (height - cropHeight) / 2;

			RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
			hv.setup(mImageMatrix, imageRect, cropRect, mCircleCrop,
					mAspectX != 0 && mAspectY != 0);

			mImageView.mHighlightViews.clear(); // Thong added for rotate

			mImageView.add(hv);
		}

		// Scale the image down for faster face detection.
		private Bitmap prepareBitmap() {

			if (mBitmap == null) {

				return null;
			}

			// 256 pixels wide is enough.
			if (mBitmap.getWidth() > 256) {

				mScale = 256.0F / mBitmap.getWidth();
			}
			Matrix matrix = new Matrix();
			matrix.setScale(mScale, mScale);
			return Bitmap.createBitmap(mBitmap, 0, 0, mBitmap.getWidth(),
					mBitmap.getHeight(), matrix, true);
		}

		public void run() {

			mImageMatrix = mImageView.getImageMatrix();
			Bitmap faceBitmap = prepareBitmap();

			mScale = 1.0F / mScale;
			if (faceBitmap != null && mDoFaceDetection) {
				FaceDetector detector = new FaceDetector(faceBitmap.getWidth(),
						faceBitmap.getHeight(), mFaces.length);
				mNumFaces = detector.findFaces(faceBitmap, mFaces);
			}

			if (faceBitmap != null && faceBitmap != mBitmap) {
				faceBitmap.recycle();
			}

			mHandler.post(new Runnable() {
				public void run() {

					mWaitingToPick = mNumFaces > 1;
					if (mNumFaces > 0) {
						for (int i = 0; i < mNumFaces; i++) {
							handleFace(mFaces[i]);
						}
					} else {
						makeDefault();
					}
					mImageView.invalidate();
					if (mImageView.mHighlightViews.size() == 1) {
						mCrop = mImageView.mHighlightViews.get(0);
						mCrop.setFocus(true);
					}
					if (mNumFaces > 1) {
						fragmentDelegate.toast("Multi face crop help");
					}
				}
			});
		}
	};

	/**
	 * @author nick
	 */
	public interface ImageCropFragmentDelegate {
		/**
		 * finish
		 */
		public void onFinish();
		/**
		 * @param message
		 */
		public void toast(String message);
		/**
		 * @return should scale
		 */
		public boolean getScaleIfNeeded();
		/**
		 * @return scale
		 */
		public boolean getScale();
		/**
		 * @return y output
		 */
		public int getOutputY();
		/**
		 * @return x output
		 */
		public int getOutputX();
		/**
		 * @return y aspect
		 */
		public int aspectY();
		/**
		 * @return x aspect
		 */
		public int aspectX();
		/**
		 * @return image path
		 */
		public String imagePath();
		/**
		 * @return should perform a circle crop
		 */
		public boolean shouldCircleCrop();
	}
}
