package com.jurassic.core.bus.impl;

import com.jurassic.core.bus.Constant;
import com.jurassic.core.bus.EventBus;
import com.jurassic.core.bus.error.ComponentNotFoundErr;
import com.jurassic.core.bus.error.EventBusNotStartedErr;
import com.jurassic.core.bus.error.EventNotFiredErr;
import com.jurassic.core.classloader.ComponentClassLoader;
import com.jurassic.core.classloader.JurassicRootClassLoader;
import com.jurassic.core.dataflow.handler.impl.ReflectionHandler;
import com.jurassic.core.dataflow.handler.impl.StaticsHandler;
import com.jurassic.core.dataflow.worker.BatchEventWorker;
import com.jurassic.core.dataflow.worker.SingleEventWorker;
import com.jurassic.core.event.ReflectionEvent;
import com.jurassic.core.processor.WorkHandler;
import com.jurassic.core.progress.handler.impl.SubProgressInvokeHandler;
import com.jurassic.core.progress.handler.pin.Pin;
import com.jurassic.core.progress.task.EndTask;
import com.jurassic.core.progress.task.StartTask;
import com.jurassic.core.progress.task.SubProgressInvokeTask;
import com.jurassic.core.progress.worker.BatchTaskWorker;
import com.jurassic.core.processor.EventProcessor;
import com.jurassic.core.processor.impl.BatchEventProcessor;
import com.jurassic.core.processor.impl.SingleEventProcessor;
import com.jurassic.core.deploy.DeployContext;
import com.jurassic.core.progress.handler.impl.EndHandler;
import com.jurassic.core.progress.handler.impl.StartHandler;
import com.jurassic.core.event.FireDelayEvent;
import com.jurassic.core.event.Event;
import com.jurassic.core.global.GlobalInstRegisterTable;
import com.jurassic.core.progress.Progress;
import com.jurassic.core.progress.manager.ProgressManager;
import com.jurassic.core.progress.worker.SingleTaskWorker;
import com.jurassic.core.resource.ResourceFactory;
import com.jurassic.core.resource.ResourceFactoryTbl;
import com.jurassic.core.util.CopyOnWriteMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EventBus??????????????
 * 
 * @author yzhu
 */
public class EBus implements EventBus, ResourceFactoryTbl, ProgressManager {
	private static final Logger logger = LoggerFactory.getLogger(EBus.class);

	// ??????????????????
	private final JurassicRootClassLoader _rootClassLoader;
	// ????????
	private volatile boolean _started = false;
	// ????????????????
	// ????????4??????????????:
	// 1.start??????,??????????????*_start????
	// 2.end??????,??????????????*_end????
	// 3.statics??????,????????????????????????????,??????????
	// 4.reflection??????,??????????????????????????
	// ??4????????????????????????????????,??????????????????????????????
	public static final String COMPONENT_KEY_START = "start";
	public static final String COMPONENT_KEY_END = "end";
	public static final String COMPONENT_KEY_STATICS = "statics";
	public static final String COMPONENT_KEY_REFLECTOR = "reflection";
	// ????????????????????????????
	private final CopyOnWriteMap<String, DeployContext> _contexts
			= new CopyOnWriteMap<>();
	// ????????,??????????????????????????????????????
	private ExecutorService _daemon;
	// ??????????????????????
	private Map<String, ResourceFactory<?>> _resourceFactoryTbl;
	private Timer _timer;// ????

	public EBus(JurassicRootClassLoader clzLoader) {
		this._rootClassLoader = clzLoader;
		// EventBus??????????????????????????????????????????????????????????????
		GlobalInstRegisterTable.register(EventBus.GLOBAL_KEY, this);
		GlobalInstRegisterTable.register(ProgressManager.GLOBAL_KEY, this);
		GlobalInstRegisterTable.register(ResourceFactoryTbl.GLOBAL_KEY, this);
	}

	public void schedule(Event event){
		// event.getScheduleSpan()??????????
		this._timer.schedule(new TimerTask() {
			public void run() {
				event.reset();
				try {
					fire(event.getPackageKey(), event.getEventKey(), event);
				} catch (Throwable ignored) {}
			}
		}, event.getScheduleSpan());
	}

	public void fire(String packageKey, String handlerKey, Event event) throws EventBusNotStartedErr,
			ComponentNotFoundErr, EventNotFiredErr {
		if (!this._started) {
			// ??????????????????????????????????
			logger.error("event bus not started");
			throw new EventBusNotStartedErr();
		}
		// ????*start,*end,statics,reflect,sub_progress_invoke??????????
		// ????????processor????????
		if (handlerKey.endsWith(StartTask.START_SUFFIX)) {
			if (!this._starter.doEvent((StartTask)event)) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
			return;
		} else if (handlerKey.endsWith(EndTask.END_SUFFIX)) {
			if (!this._ender.doEvent((EndTask)event)) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
			return;
		} else if (handlerKey.equals(EBus.COMPONENT_KEY_STATICS)) {
			if (!this._statics.doEvent(event)) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
			return;
		} else if (handlerKey.equals(EBus.COMPONENT_KEY_REFLECTOR)) {
			if (!this._reflector.doEvent((ReflectionEvent) event)) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
			return;
		} else if (handlerKey.equals(SubProgressInvokeTask.KEY)) {
			if (!this._subProgressInvoker.doEvent((SubProgressInvokeTask) event)) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
		}
		if (packageKey != null && !"".equals(packageKey)) {
			// ??????????package??????????????????????????????????
			DeployContext context = this._contexts.get(packageKey);
			if (context == null) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
			if (!context.doEvent(handlerKey, event)) {
				logger.error("event " + event + " not deal");
				throw new EventNotFiredErr("event not fire");
			}
		} else {
			// ??????package??????????????????????
			Map<String, DeployContext> map = this._contexts.toMap();
			for (String key : map.keySet()) {
				DeployContext context = map.get(key);
				context.doEvent(handlerKey, event);
			}
		}
	}

	public synchronized void shutdown() {
		if (!this._started)
			return;

		// started??????false????????????????fire????????
		// ??????????fire??????????????????
		this._started = false;

		// ??????????????,????undeploy
		String[] keys = this._contexts.toMap()
				.keySet().toArray(new String[0]);
		for (String key : keys) {
			this.undeploy(key);
		}

		// ????start??end??statics,reflect??????????????
		logger.info("shutdown start processor");
		this._starter.shutdown();
		logger.info("shutdown end processor");
		this._ender.shutdown();
		logger.info("shutdown statics processor");
		this._statics.shutdown();
		logger.info("shutdown reflect processor");
		this._reflector.shutdown();
		logger.info("shutdown sub progress invoker progress");
		this._subProgressInvoker.shutdown();

		// ??????????????????????
		this._daemon.shutdown();
		this._timer.cancel();

		logger.info("event bus shutdown");
	}

	/**
	 * ????????start??????
	 */
	private EventProcessor<StartTask> _starter;
	private void addStarter() {
		this._starter = new BatchEventProcessor<>(
				new StartHandler(),
				new BatchTaskWorker<>(),
				Constant.DRPT_DATA_SIZE_POWER);
		logger.info("start starter processor");
		this._starter.start(this._daemon);
	}

	/**
	 * ????????end??????
	 */
	private EventProcessor<EndTask> _ender;
	private void addEnder() {
		this._ender = new BatchEventProcessor<>(
				new EndHandler(),
				new BatchTaskWorker<>(),
				Constant.DRPT_DATA_SIZE_POWER);
		logger.info("start ender processor");
		this._ender.start(this._daemon);
	}

	/**
	 * ????????statics??????
	 */
	private EventProcessor<Event> _statics;
	private void addStatics() {
		this._statics = new BatchEventProcessor<>(
				new StaticsHandler(),
				new BatchEventWorker<>(),
				Constant.DRPT_DATA_SIZE_POWER);
		logger.info("start statics processor");
		this._statics.start(this._daemon);
	}

	/**
	 * ??????????reflector??????
	 */
	private EventProcessor<ReflectionEvent> _reflector;
	@SuppressWarnings("unchecked")
	private void addReflector() {
		this._reflector = new SingleEventProcessor<ReflectionEvent>(
				new ReflectionHandler(),
				new WorkHandler[]{new SingleEventWorker<>()},
				Constant.DRPT_DATA_SIZE_POWER);
		logger.info("start reflector processor");
		this._reflector.start(this._daemon);
	}

	/**
	 * ??????????SubProgressInvoke??????
	 */
	private EventProcessor<SubProgressInvokeTask> _subProgressInvoker;
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void addSubProgressInvoker() {
		this._subProgressInvoker = new SingleEventProcessor<SubProgressInvokeTask>(
				new SubProgressInvokeHandler(),
				new WorkHandler[]{new SingleTaskWorker()},
				Constant.DRPT_DATA_SIZE_POWER);
		logger.info("start sub-progress invoker");
		this._subProgressInvoker.start(this._daemon);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public synchronized boolean start() {
		if (this._started)
			return true;
		this._daemon = Executors.newCachedThreadPool();
		logger.info("start event bus");
		// ????????????????
		this._resourceFactoryTbl = new HashMap<>();
		// ??????????start??end??statics,reflect,sub-progress-invoke??????
		this.addStarter();
		this.addEnder();
		this.addStatics();
		this.addReflector();
		this.addSubProgressInvoker();
		this._started = true;
		// ????timer
		this._timer = new Timer("timer");
		// 1ms??????timer??????????????????processor??
		this._timer.schedule(this._delayTask, 1000, 1);

		return true;
	}
	private TimerTask _delayTask = new TimerTask() {
		public void run() {
			try {
				// ??????????????????DelayFireEvent
				// ????????????processor????????????????????????
				Map<String, DeployContext> map = _contexts.toMap();
				for (String key : map.keySet()) {
					DeployContext context = map.get(key);
					context.doEvent(null, FireDelayEvent.instance);
				}
				// ????????????????FireDelayEvent,????Processor??????????delay????
				((EventProcessor) _starter).doEvent(FireDelayEvent.instance);
				((EventProcessor) _ender).doEvent(FireDelayEvent.instance);
				_statics.doEvent(FireDelayEvent.instance);
				((EventProcessor) _reflector).doEvent(FireDelayEvent.instance);
				((EventProcessor) _subProgressInvoker).doEvent(FireDelayEvent.instance);
			} catch (Throwable ex) {
				logger.warn(ex.getMessage());
			}
		}
	};

	public synchronized boolean deploy(DeployContext context) {
		if (context.deploy()) {
			// ??????????
			this._contexts.set(context.getPackageKey(), context);
			this._contexts.flush();
			context.start();
			return true;
		} else {
			return false;
		}
	}

	public synchronized void undeploy(String packageKey) {
		DeployContext context = this._contexts.remove(packageKey);
		if (context != null && context.isDeployed()) {
			this._contexts.flush();
			// ??????????
			context.undeploy();
			// ????????????????????????????????
			this._rootClassLoader.unbindComponentLoader(packageKey);
		}
	}

	public void loadResourceFactory(String classOfFactory, String id,
									Map<String, String> params) throws Throwable {
		// ????????????class
		ComponentClassLoader commonLibLoader = this._rootClassLoader.getCommonLibLoader();
		Class<?> _class = commonLibLoader.loadClass(classOfFactory);
		ResourceFactory<?> factory = (ResourceFactory<?>) _class.newInstance();
		// ??????????????
		factory.init(params);
		this._resourceFactoryTbl.put(id, factory);
	}

	public ResourceFactory<?> getResourceFactory(String id) {
		return this._resourceFactoryTbl.get(id);
	}

	public Progress createProgress(String packageKey,
								   String progressKey,
								   Pin[] params,
								   Progress parent){
		DeployContext context = this._contexts.get(packageKey);
		if (context == null) {
			return null;
		}
		// ??????????????????????????????
		return context.createProgress(progressKey, params, parent);
	}

	public Event createEvent(String packageKey, String eventKey, Object[] params) {
		DeployContext context = this._contexts.get(packageKey);
		if (context == null) {
			return null;
		}
		return context.createEvent(eventKey, params);
	}
}
