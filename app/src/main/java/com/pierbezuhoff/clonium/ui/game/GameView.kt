package com.pierbezuhoff.clonium.ui.game

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.pierbezuhoff.clonium.models.GameModel
import com.pierbezuhoff.clonium.utils.Once
import org.koin.core.KoinComponent
import org.koin.core.get
import java.lang.IllegalArgumentException

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), KoinComponent {
    lateinit var viewModel: GameViewModel // inject via data binding

    init {
        holder.addCallback(SurfaceManager { viewModel.gameModel } )
        get<GameGestures>().registerAsOnTouchListenerFor(this)
    }
}

class SurfaceManager(liveGameModelInitializer: () -> LiveData<GameModel>) : Any()
    , SurfaceHolder.Callback
    , LifecycleOwner
{
    private val lifecycleRegistry = LifecycleRegistry(this)
    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    private lateinit var drawThread: DrawThread
    private val firstSurfaceChange by Once(true)
    private lateinit var size: Pair<Int, Int>
    private val gameModel: LiveData<GameModel> by lazy(liveGameModelInitializer)

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        drawThread = DrawThread(gameModel, holder).apply {
            start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        Log.i("SurfaceManager", "surfaceChanged($holder, $format, $width, $height)")
        size = Pair(width, height)
        if (firstSurfaceChange)
            gameModel.observe(this, Observer {
                it.setSize(size.first, size.second)
            })
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        drawThread.ended = true
        drawThread.join() // NOTE: may throw some exceptions
    }
}

// MAYBE: it can be cancellable coroutine
// MAYBE: rewrap LiveData into Connection
class DrawThread(
    private val liveCallback: LiveData<out Callback>,
    private val surfaceHolder: SurfaceHolder
) : Thread() {
    interface Callback {
        fun advance(timeDelta: Long)
        fun draw(canvas: Canvas)
    }
    var ended = false
    private var lastUpdateTime: Long = 0L

    override fun run() {
        var maybeCanvas: Canvas? = null
        while (!ended) {
            val currentTime = System.currentTimeMillis()
            val timeDelta = currentTime - lastUpdateTime
            if (timeDelta >= UPDATE_TIME_DELTA) {
                if (lastUpdateTime != 0L)
                    liveCallback.value?.advance(timeDelta)
                lastUpdateTime = currentTime
            }
            try {
                surfaceHolder.lockCanvas()?.also { canvas: Canvas ->
                    maybeCanvas = canvas
                    synchronized(surfaceHolder) {
                        liveCallback.value?.draw(canvas)
                    }
                }
            } catch (e: IllegalArgumentException) { // surface already locked
            } catch (e: Exception) {
                e.printStackTrace()
                Log.w("DrawThread", "include exception $e into silent catch")
            } finally {
                maybeCanvas?.let {
                    surfaceHolder.unlockCanvasAndPost(it)
                }
            }
        }
    }

    companion object {
        private const val FPS = 60
        private const val UPDATE_TIME_DELTA: Long = 1000L / FPS
    }
}