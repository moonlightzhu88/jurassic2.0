package com.jurassic.core.deploy;

import com.jurassic.core.annotation.Bean;
import com.jurassic.core.annotation.Component;
import com.jurassic.core.annotation.Configuration;
import com.jurassic.core.annotation.Param;
import com.jurassic.core.bus.Constant;
import com.jurassic.core.bus.EventBus;
import com.jurassic.core.classloader.ComponentClassLoader;
import com.jurassic.core.classloader.JurassicRootClassLoader;
import com.jurassic.core.compiler.element.ProgressElement;
import com.jurassic.core.dataflow.filter.Filter;
import com.jurassic.core.dataflow.handler.BaseEventHandler;
import com.jurassic.core.dataflow.handler.BatchEventHandler;
import com.jurassic.core.dataflow.handler.EventHandler;
import com.jurassic.core.dataflow.worker.BatchEventWorker;
import com.jurassic.core.dataflow.worker.SingleEventWorker;
import com.jurassic.core.event.Event;
import com.jurassic.core.event.EventMonitor;
import com.jurassic.core.global.GlobalInstRegisterTable;
import com.jurassic.core.handler.AbstractHandler;
import com.jurassic.core.handler.DeployProperties;
import com.jurassic.core.handler.HandlerConfig;
import com.jurassic.core.processor.EventProcessor;
import com.jurassic.core.processor.WorkHandler;
import com.jurassic.core.processor.impl.BatchEventProcessor;
import com.jurassic.core.processor.impl.SingleEventProcessor;
import com.jurassic.core.progress.Progress;
import com.jurassic.core.progress.ProgressMonitor;
import com.jurassic.core.progress.factory.ProgressFactory;
import com.jurassic.core.progress.factory.ProgressTemplate;
import com.jurassic.core.progress.handler.BatchTaskHandler;
import com.jurassic.core.progress.handler.EPU;
import com.jurassic.core.progress.handler.TaskHandler;
import com.jurassic.core.progress.handler.pin.Pin;
import com.jurassic.core.progress.worker.BatchTaskWorker;
import com.jurassic.core.progress.worker.EPUWorker;
import com.jurassic.core.progress.worker.SingleTaskWorker;
import com.jurassic.core.resource.AttachedResourceTbl;
import com.jurassic.core.resource.ResourceFactoryTbl;
import com.jurassic.core.resource.ResourceHandlerFactory;
import com.jurassic.core.thread.ComponentThreadPool;
import com.jurassic.core.util.TypedUtil;
import com.jurassic.core.compiler.Compiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * ????????????????
 * ??????????????????????????????????????????
 * ??????????????????????????
 * ??????????????????????????????????????????
 * ??????????????????????????
 *
 * ??????????????
 * ProcessTemplate??????
 * @Configuration
 * class ...{
 *     @Bean
 *     ProcessTemplate ...(...);
 * }
 * Handler????
 * @Component
 * class ... extends AbstractHandler{
 *     ...
 * }
 * @Configuration
 * class ...{
 *     @Bean
 *     Map<String, HandlerConfig> ...();
 * }
 * Filter????
 * @Component
 * class ... implements Filter{
 *     ...
 * }
 * ??????????
 * @Configuration
 * class ...{
 *     @Bean
 *     ThreadFactory ...();
 * }
 * DeployAware??UnDeployAware??????
 * @Configuration
 * class ...{
 *     @Bean
 *     DeployAware(UnDeployAware) ...();
 * }
 * ProgressMonitor??EventMonitor??????
 * @Configuration
 * class ...{
 *     @Bean
 *     Map<String, ProgressMonitor(EventMonitor)> ...();
 * }
 * 
 * @author yzhu
 * 
 */
public class DeployContext {
	private static final Logger logger = LoggerFactory.getLogger(DeployContext.class);

	private final String _packageKey;// ????key
	private volatile boolean _deploy = false;
	private ResourceHandlerFactory _resourceHandlerFactory;
	private ComponentClassLoader _classLoader;// ????????
	private DeployProperties _properties;// ????????
	private ComponentThreadPool _threadPool;// ??????
	// ????????
	private ProgressFactory _progressFactory = new ProgressFactory();
	// ????????????
	private Map<String, EventProcessor<?>> _processors = new HashMap<>();
	// ????????????????????????
	private List<DeployAware> _deployAwares = new ArrayList<>();
	private List<UnDeployAware> _undeployAwares = new ArrayList<>();
	// ????????????
	private Map<String, ProgressMonitor> _progressMonitors = new HashMap<>();
	// ????????????
	private Map<String, EventMonitor> _eventMonitors = new HashMap<>();

	public DeployContext(String packageKey, String rootFilePath, URL[] urls) {
		this._packageKey = packageKey;
		// ????????????
		this._classLoader = GlobalInstRegisterTable
				.getInst(JurassicRootClassLoader.GLOBAL_KEY,
						JurassicRootClassLoader.class)
				.bindComponentLoader(packageKey, urls);
		// ????????????????
		this._resourceHandlerFactory =
			new ResourceHandlerFactory(new AttachedResourceTbl(),
				GlobalInstRegisterTable.getInst(
						ResourceFactoryTbl.GLOBAL_KEY, ResourceFactoryTbl.class));

		// ????????
		String fileName =
				rootFilePath + "/" + DeployProperties.CONF_NAME;
		File file = new File(fileName);
		if (!file.exists()) {
			// ??????????????????????????????
			logger.warn("there is no deploy.conf in " + rootFilePath);
		}
		this._properties = new DeployProperties(file);
	}

	public ComponentClassLoader getClassLoader() {
		return this._classLoader;
	}

	/**
	 * ??????????????????
	 */
	public DeployProperties getProperties() {
		return this._properties;
	}

	/**
	 * ????bean
	 */
	@SuppressWarnings("unchecked")
	private void registerBean(Map<String, Object> tbl, String key, Object bean) {
		if (logger.isDebugEnabled())
			logger.debug("register bean[" + key + "]," + bean.getClass());
		Object orignal = tbl.get(key);
		if (orignal == null) {
			// ????key-object
			tbl.put(key, bean);
		} else {
			if (orignal instanceof List) {
				// ????????????????????????object??????????????????
				List<Object> list = (List<Object>) orignal;
				list.add(bean);
			} else {
				// ????????????????????object??????????instance????list
				List<Object> list = new ArrayList<>();
				list.add(orignal);
				list.add(bean);
				tbl.put(key, list);
			}
		}
	}

	/**
	 * ??????????@Param????
	 */
	private Param getParamAnnotation(Annotation[] annotations) {
		if (annotations == null)
			return null;
		for (Annotation annotation : annotations) {
			if (annotation.annotationType().equals(Param.class)) {
				return (Param) annotation;
			}
		}
		return null;
	}

	/**
	 * ????@Configuration??@Bean
	 */
	private boolean registerConfiguration(
			Map<String, Object> tbl, Object configuration,
			Method method, Queue<Object[]> delayBeans) throws Exception{
		Bean beanAnnotation = method.getAnnotation(Bean.class);

		// ??public??????????@Bean
		Type[] typeOfParams = method.getGenericParameterTypes();
		Annotation[][] annotationOfParameters = method.getParameterAnnotations();

		if (typeOfParams.length == 0) {
			// ????????????????????????????????????????????????Bean
			Object instance = method.invoke(configuration);
			// ????@Bean??????name????????????????Bean??key
			// ??????????????key
			if (beanAnnotation != null) {
				String key = !"".equals(beanAnnotation.name()) ?
						beanAnnotation.name() : method.getGenericReturnType().getTypeName();
				this.registerBean(tbl, key, instance);
			}
			return true;
		} else {
			// ??????????????????????????bean
			Object[] params = new Object[typeOfParams.length];
			boolean delay = false;// ??????????????????????????
			for (int i = 0; i < params.length; i++) {
				Param paramAnnotation = this.getParamAnnotation(annotationOfParameters[i]);
				// ??????????????@Param??????????name??????????????
				Object bean = tbl.get(paramAnnotation != null ?
						paramAnnotation.name() : typeOfParams[i].getTypeName());
				if (bean != null) {
					params[i] = bean;
				} else {
					// ??????????????bean????????????????????????????@Bean????????
					delayBeans.add(
							new Object[]{configuration, method});
					if (logger.isDebugEnabled()) {
						logger.debug("delay config method[" +
								configuration.getClass().getTypeName() + "," +
								method.getName() + "] because param[" +
								typeOfParams[i].getTypeName() + "] not available");
					}
					delay = true;
					break;// ??????????????
				}
			}
			if (!delay) {
				// ????????????????????method??????????Bean
				Object bean = method.invoke(configuration, params);
				if (beanAnnotation != null) {
					String key = !"".equals(beanAnnotation.name()) ?
							beanAnnotation.name() : method.getGenericReturnType().getTypeName();
					this.registerBean(tbl, key, bean);
				}
				return true;
			}
		}

		return false;
	}

	/**
	 * ??????????????????
	 */
	private Map<String, Object> registerBeans(List<Class<?>> classes) {
		try {
			Map<String, Object> tbl = new HashMap<>();
			// ????????
			this.registerBean(tbl, EventBus.class.getTypeName(),
					GlobalInstRegisterTable.getInst(
							EventBus.GLOBAL_KEY, EventBus.class
					));
			// ????????????????
			this.registerBean(tbl, ResourceHandlerFactory.class.getTypeName(),
					this._resourceHandlerFactory);
			// ????????????????
			this.registerBean(tbl, this.getClass().getTypeName(), this);
			// @Configuration????????
			List<Object> configurationObjects = new ArrayList<>();

			for (Class<?> clz : classes) {
				Component componentAnnotation = clz.getAnnotation(Component.class);
				Configuration configurationAnnotation = clz.getAnnotation(Configuration.class);
				if (componentAnnotation != null) {
					// @Component??????class
					Object bean = clz.newInstance();
					// ????@Configuration??????name????????????????Bean??key
					// ??????????????key
					String key = !"".equals(componentAnnotation.name()) ?
							componentAnnotation.name() : clz.getTypeName();
					this.registerBean(tbl, key, bean);
				} else if (configurationAnnotation != null) {
					// @Configuration??????class
					configurationObjects.add(clz.newInstance());
				}
			}

			// ????@Conguration????????????@Bean????????????????Bean
			Queue<Object[]> delayBeans = new LinkedList<>();// ????????????????????????method
			for (Object configuration : configurationObjects) {
				Class<?> clz = configuration.getClass();
				Method[] methods = clz.getDeclaredMethods();
				for (Method method : methods) {
					this.registerConfiguration(tbl, configuration, method, delayBeans);
				}
			}
			// ????????lazyBean????????????????
			int size = delayBeans.size();
			int num = 0;
			while (!delayBeans.isEmpty()) {
				Object[] dataOfInvoke = delayBeans.poll();
				if (this.registerConfiguration(tbl, dataOfInvoke[0],
						(Method) dataOfInvoke[1], delayBeans)) {
					// ????????????bean??????delayBean??????????1??????????num????
					size--;
					num = 0;
				} else {
					// num??????1????????????????method??????Bean????????????????????Bean????
					num++;
				}
				if (size == num) {
					// ????????????????????Bean????????delayBeans??????
					// ??????lazyBeans????????method??????????
					// Bean????????????????????
					break;
				}
			}
			if (!delayBeans.isEmpty()){
				// ????????????????Bean????????????
				return null;
			}
			return tbl;
		} catch (Throwable ex) {
			logger.error(ex.getMessage(), ex);
			return null;
		}
	}

	/**
	 * ????ProgressTemplate
	 */
	@SuppressWarnings({"rawtypes","unchecked"})
	private void registerProcessTemplate(Map<String, Object> registeredBeans) {
		Object bean = registeredBeans.get(ProgressTemplate.class.getTypeName());
		if (bean != null) {
			// ????ProgressTemplate????????key????????
			if (bean instanceof List) {
				// ??????????????ProgressTemplate
				List<ProgressTemplate> templates = (List<ProgressTemplate>) bean;
				for (ProgressTemplate template : templates) {
					this._progressFactory.addTemplate(template);
				}
			} else if (bean instanceof ProgressTemplate) {
				// ??????????????ProgressTemplate
				ProgressTemplate template = (ProgressTemplate) bean;
				this._progressFactory.addTemplate(template);
			}
		}
	}

	/**
	 * ????Handler
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void registerHandler(Map<String, Object> registeredBeans) {
		// ????Handler??????????
		// ??????????????Map<String, HandlerConfig>
		// ????key??Handler??name??value??????????
		String typeName =
				new TypedUtil<Map<String, HandlerConfig>>() {
					@Override
					public String getActualTypeName() {
						Type typeOfSuperClz = this.getClass().getGenericSuperclass();
						ParameterizedType pt = (ParameterizedType)typeOfSuperClz;
						return pt.getActualTypeArguments()[0].getTypeName();
					}
				}.getActualTypeName();
		Object configOfHandlers = registeredBeans.get(typeName);
		Map<String, HandlerConfig> combindedConfigOfHandlers = new HashMap<>();
		// ????????????????????
		if (configOfHandlers != null) {
			if (configOfHandlers instanceof List) {
				// @Configuration??????????Handler
				for (Map<String, HandlerConfig> configs : (List<Map<String, HandlerConfig>>) configOfHandlers) {
					combindedConfigOfHandlers.putAll(configs);
				}
			} else {
				// @Configuration??????1??Handler
				Map<String, HandlerConfig> configs = (Map<String, HandlerConfig>) configOfHandlers;
				combindedConfigOfHandlers.putAll(configs);
			}
		}
		// ??????????????Handler
		Map<String, AbstractHandler> handlers = new HashMap<>();
		for (Map.Entry<String, Object> entry : registeredBeans.entrySet()) {
			Object bean = entry.getValue();
			if (bean instanceof AbstractHandler) {
				// bean??Handler????
				AbstractHandler handler = (AbstractHandler) bean;
				// ????Handler??key
				HandlerConfig config = combindedConfigOfHandlers.get(handler.getHandlerKey());
				if (config != null) {
					// ????????????????????????????????
					handler.config(config.getNumOfThread(), config.getPowerOfBuffer());
				}
				// ????????????
				handler.loadProperties(this._properties);
				// ????????????
				handler.resourceInject(this._resourceHandlerFactory);
				handlers.put(handler.getHandlerKey(), handler);
			} else if (bean instanceof List) {
				for (Object object : (List) bean) {
					if (object instanceof AbstractHandler) {
						// bean??Handler????
						AbstractHandler handler = (AbstractHandler) object;
						// ????Handler??key
						HandlerConfig config = combindedConfigOfHandlers.get(handler.getHandlerKey());
						if (config != null) {
							// ????????????????????????????????
							handler.config(config.getNumOfThread(), config.getPowerOfBuffer());
						}
						// ????????????
						handler.loadProperties(this._properties);
						// ????????????
						handler.resourceInject(this._resourceHandlerFactory);
						handlers.put(handler.getHandlerKey(), handler);
					}
				}
			}
		}
		// ??????????????Filter
		for (Map.Entry<String, Object> entry : registeredBeans.entrySet()) {
			Object bean = entry.getValue();
			if (bean instanceof Filter) {
				Filter filter = (Filter) bean;
				AbstractHandler handler = handlers.get(filter.getHandlerKey());
				if (handler instanceof BaseEventHandler) {
					// ??????????handler
					if (filter.isBefore()) {
						((BaseEventHandler) handler).addBeforeFilter(filter);
					} else {
						((BaseEventHandler) handler).addAfterFilter(filter);
					}
				}
			} else if (bean instanceof List) {
				for (Object object : (List) bean) {
					if (object instanceof Filter) {
						Filter filter = (Filter) object;
						AbstractHandler handler = handlers.get(filter.getHandlerKey());
						if (handler instanceof BaseEventHandler) {
							// ??????????handler
							if (filter.isBefore()) {
								((BaseEventHandler) handler).addBeforeFilter(filter);
							} else {
								((BaseEventHandler) handler).addAfterFilter(filter);
							}
						}
					}
				}
			}
		}
		// ??handlers????????????Processors
		for (Map.Entry<String, AbstractHandler> entry : handlers.entrySet()) {
			String handlerKey = entry.getKey();
			AbstractHandler handler = entry.getValue();
			EventProcessor<?> processor = null;
			if (handler instanceof BatchTaskHandler) {
				// ??????????????
				processor = new BatchEventProcessor(
						handler, new BatchTaskWorker(), handler.getPowerOfBuffer());
			} else if (handler instanceof TaskHandler) {
				// ??????????????
				WorkHandler[] workers = new WorkHandler[handler.getNumOfThread()];
				for (int i = 0; i < workers.length; i++)
					workers[i] = new SingleTaskWorker();
				processor = new SingleEventProcessor(
						handler, workers, handler.getPowerOfBuffer());
			} else if (handler instanceof BatchEventHandler) {
				// ??????????????
				processor = new BatchEventProcessor(
						handler, new BatchEventWorker(), handler.getPowerOfBuffer());
			} else if (handler instanceof EventHandler) {
				// ??????????????
				WorkHandler[] workers = new WorkHandler[handler.getNumOfThread()];
				for (int i = 0; i < workers.length; i++)
					workers[i] = new SingleEventWorker();
				processor = new SingleEventProcessor(
						handler, workers, handler.getPowerOfBuffer());
			}
			if (processor != null) {
				this._processors.put(handlerKey, processor);
			}
		}
	}

	/**
	 * ????ThreadPool
	 */
	private void initThreadPool(Map<String, Object> registeredBeans) {
		// ??????????????ThreadFactory
		Object bean = registeredBeans.get(ThreadFactory.class.getTypeName());
		ThreadFactory threadFactory = bean != null ?
				(ThreadFactory) bean : new DefaultThreadFactory();
		// ????????????????,??????????????????handler????????
		int threadNum = 0;
		for (Map.Entry<String, EventProcessor<?>> entry : this._processors.entrySet()) {
			// ????????????????????????????????????????
			EventProcessor<?> processor = entry.getValue();
			threadNum += processor.getHandler().getNumOfThread();
		}
		logger.info("init threadpool, thread size:" + threadNum);
		this._threadPool = new ComponentThreadPool(threadNum > 0 ? threadNum : 1,
				threadFactory, this._classLoader,
				this._resourceHandlerFactory.getAttachedResourceTbl());
	}

	/**
	 * ????processor
	 */
	private boolean startProcessors() {
		for (Map.Entry<String, EventProcessor<?>> entry : this._processors.entrySet()) {
			EventProcessor<?> processor = entry.getValue();
			// ????Processor
			if (!processor.start(this._threadPool)) {
				logger.warn("start processor[" + entry.getKey() + "] fail");
				return false;
			}
		}
		return true;
	}

	/**
	 * ????Processor
	 */
	private void shutdownProcessors() {
		for (Map.Entry<String, EventProcessor<?>> entry : this._processors.entrySet()) {
			EventProcessor<?> processor = entry.getValue();
			// ????Processor
			if (processor.isStarted())
				processor.shutdown();
		}
	}

	/**
	 * ????DeployAware????
	 */
	@SuppressWarnings("unchecked")
	private void registerDeployAwares(Map<String, Object> registeredBeans) {
		Object bean = registeredBeans.get(DeployAware.class.getTypeName());
		if (bean != null) {
			if (bean instanceof List) {
				List<DeployAware> awares = (List<DeployAware>)bean;
				this._deployAwares.addAll(awares);
			} else {
				this._deployAwares.add((DeployAware) bean);
			}
		}
	}

	/**
	 * ????UnDeployAware????
	 */
	@SuppressWarnings("unchecked")
	private void registerUnDeployAwares(Map<String, Object> registeredBeans) {
		Object bean = registeredBeans.get(UnDeployAware.class.getTypeName());
		if (bean != null) {
			if (bean instanceof List) {
				List<UnDeployAware> awares = (List<UnDeployAware>)bean;
				this._undeployAwares.addAll(awares);
			} else {
				this._undeployAwares.add((UnDeployAware) bean);
			}
		}
	}

	/**
	 * ??????????????????????
	 */
	@SuppressWarnings("unchecked")
	private void registerMonitor(Map<String, Object> registeredBeans) {
		String typeName =
				new TypedUtil<Map<String, ProgressMonitor>>() {
					@Override
					public String getActualTypeName() {
						Type typeOfSuperClz = this.getClass().getGenericSuperclass();
						ParameterizedType pt = (ParameterizedType)typeOfSuperClz;
						return pt.getActualTypeArguments()[0].getTypeName();
					}
				}.getActualTypeName();
		Object bean = registeredBeans.get(typeName);
		// ????????????????????
		if (bean != null) {
			if (bean instanceof List) {
				// @Configuration??????????ProgressMonitor
				for (Map<String, ProgressMonitor> monitors : (List<Map<String, ProgressMonitor>>) bean) {
					this._progressMonitors.putAll(monitors);
				}
			} else {
				// @Configuration??????1??ProgressMonitor
				Map<String, ProgressMonitor> monitors = (Map<String, ProgressMonitor>) bean;
				this._progressMonitors.putAll(monitors);
			}
		}
		typeName =
				new TypedUtil<Map<String, EventMonitor>>() {
					@Override
					public String getActualTypeName() {
						Type typeOfSuperClz = this.getClass().getGenericSuperclass();
						ParameterizedType pt = (ParameterizedType)typeOfSuperClz;
						return pt.getActualTypeArguments()[0].getTypeName();
					}
				}.getActualTypeName();
		bean = registeredBeans.get(typeName);
		// ????????????????????
		if (bean != null) {
			if (bean instanceof List) {
				// @Configuration??????????ProgressMonitor
				for (Map<String, EventMonitor> monitors : (List<Map<String, EventMonitor>>) bean) {
					this._eventMonitors.putAll(monitors);
				}
			} else {
				// @Configuration??????1??ProgressMonitor
				Map<String, EventMonitor> monitors = (Map<String, EventMonitor>) bean;
				this._eventMonitors.putAll(monitors);
			}
		}
	}

	/**
	 * ??????????????????????
	 */
	private void loadProgressTemplateFromPath(String path) {
		List<URL> templateUrls = new ArrayList<>();
		if (path.startsWith("classpath:")) {
			// ??classpath??????
			List<URL> urls = this._classLoader.getResourceFromPath(
					path.substring(10), (dir, name) -> name.endsWith(".xml"));
			if (urls != null && !urls.isEmpty()) {
				templateUrls.addAll(urls);
			}
		} else if (path.startsWith("file:")) {
			// ??????????????
			File dir = new File(path.substring(5));
			if (dir.exists()) {
				// ????progress-template????????????????????url
				File[] files = dir.listFiles((dir1, name) -> name.endsWith(".xml"));
				if (files != null && files.length > 0) {
					for (File file : files) {
						try {
							templateUrls.add(new URL(
									"file", null, file.getAbsolutePath()));
						} catch (Throwable ex) {
							logger.warn("progress template file:"
									+ file.getAbsolutePath() + " load fail");
						}
					}
				}
			}
		}
		if (!templateUrls.isEmpty()) {
			for (URL url : templateUrls) {
				try{
					ProgressElement element = Compiler.parseProgress(url);
					ProgressTemplate template =
							Compiler.generateProgressTemplate(
									element, this._classLoader);
					if (logger.isDebugEnabled())
						logger.debug("load progress template:" + template.getProgressKey());
					this._progressFactory.addTemplate(template);
				} catch (Throwable ex) {
					logger.warn(ex.getMessage(), ex);
					logger.warn("progress template:" + url.getFile() + " load fail");
				}
			}
		}
	}

	/**
	 * ????
	 */
	public boolean deploy() {
		if (this._deploy)
			return true;
		// ????????????????????
		if (!this._properties.isLoaded()) {
			logger.warn("load package properties fail");
			return false;
		}
		// ????@Component??@Configuration
		List<Class<?>> classes = this._classLoader
				.findClassByAnnotation(Component.class, Configuration.class);
		// ??????????@Component??@Bean
		Map<String, Object> registeredBeans = this.registerBeans(classes);
		if (registeredBeans == null) {
			logger.warn("initialize bean fail");
			return false;
		}
		// ????ProgressTemplate
		this.registerProcessTemplate(registeredBeans);
		// ????????????????ProgressTemplate
		String path = this._properties.getProperty("template.path");
		if (path != null) {
			this.loadProgressTemplateFromPath(path);
		}
		if (this._progressFactory.hasProgressTemplate()) {
			// ??????????????????????????????????????EPU
			EventProcessor processor = new BatchEventProcessor(
					new EPU(), new EPUWorker(), Constant.DEFAULT_EPU_POWER);
			this._processors.put(processor.getHandler().getHandlerKey(),
					processor);
		}
		// ????Handler
		this.registerHandler(registeredBeans);
		// ????????????????monitor
		this.registerMonitor(registeredBeans);
		// ????ThreadPool
		this.initThreadPool(registeredBeans);
		// ????DeployAware??UnDeployAware
		this.registerDeployAwares(registeredBeans);
		this.registerUnDeployAwares(registeredBeans);

		// ????Processor
		if (!this.startProcessors()) {
			// ??????????????????????????????Processor
			this.shutdownProcessors();
			this._threadPool.shutdown();
			this.destroyResource();
			this._deploy = false;
			return false;
		}
		this._deploy = true;

		return true;
	}

	public void start() {
		// ????DeployAware??startup????
		for (DeployAware aware : this._deployAwares) {
			aware.startup(this);
		}
	}

	/**
	 * ????
	 */
	public void undeploy() {
		if (!this._deploy)
			return;
		// ????????????????????????????????
		this._deploy = false;
		logger.info("undeploy package " + this._packageKey);
		// ????????????????????????,??????????????????????????
		// ????????????????????????????????????????
		logger.info("waiting for all proc finish");
		while (this._progressFactory.hasRunningProgress()) {
			LockSupport.parkNanos(1000000L);
		}
		// ????UnDeployAware??shutdown????
		for (UnDeployAware aware : this._undeployAwares) {
			aware.shutdown(this);
		}
		// ??????????Processor
		this.shutdownProcessors();
		this._threadPool.shutdown();
		this.destroyResource();
		logger.info("shutdown all processors");
	}

	/**
	 * ????????
	 */
	private void destroyResource() {
		this._resourceHandlerFactory = null;
		this._classLoader = null;
		this._properties.destroyResource();
		this._properties = null;
		this._threadPool = null;
		this._progressFactory.destroyResource();
		this._progressFactory = null;
		this._processors.clear();
		this._processors = null;
		this._deployAwares.clear();
		this._deployAwares = null;
		this._undeployAwares.clear();
		this._undeployAwares = null;
		this._progressMonitors.clear();
		this._progressMonitors = null;
		this._eventMonitors.clear();
		this._eventMonitors = null;
	}

	public boolean isDeployed() {
		return this._deploy;
	}

	public String getPackageKey() {
		return this._packageKey;
	}

	/**
	 * ??????????
	 * ????deploy????????????????????????
	 * ??deploy????
	 */
	public String[] getDependPackages() {
		String depends = this._properties.getProperty("depend");
		if (depends == null || "".equals(depends))
			return null;

		return depends.split(",");
	}

	/**
	 * ????????
	 * ??????????????????
	 */
	public Progress createProgress(
			String progressKey, Pin[] params, Progress parent) {
		if (!this._deploy) {
			// ??????deploy????????????
			logger.warn("package " + this._packageKey + " has undeployed");
			return null;
		}
		// ????????????????????
		Progress proc = this._progressFactory.createProgress(
				this._packageKey, progressKey, params, parent);
		// ??????????????????????????????????????monitor
		if (proc != null) {
			ProgressMonitor monitor = this._progressMonitors.get(progressKey);
			if (monitor != null) {
				proc.setMonitor(monitor);
			}
		} else {
			logger.warn("create progress[" + this._packageKey + ","
					+ progressKey + "] fail");
		}
		return proc;
	}

	/**
	 * ????????
	 * ????????????????????service??????????????????
	 */
	public Event createEvent(String eventKey, Object[] params) {
		if (!this._deploy) {
			// ??????deploy????????????
			logger.warn("package " + this._packageKey + " has undeployed");
			return null;
		}
		// ??????????
		EventProcessor<?> processor = this._processors.get(eventKey);
		if (processor == null) {
			logger.warn("processor[" + eventKey + "] not exist");
			return null;
		}
		AbstractHandler handler = processor.getHandler();
		if (handler instanceof BaseEventHandler) {
			// ??????????????????????????????????????task????????createProc????????
			BaseEventHandler<?> baseEventHandler = (BaseEventHandler<?>) handler;
			Event event = baseEventHandler.createEvent(params);
			if (event != null) {
				EventMonitor monitor = this._eventMonitors.get(eventKey);
				if (monitor != null)
					event.setMonitor(monitor);
			}
			return event;
		}
		return null;
	}


	/**
	 * ????????
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public boolean doEvent(String handler, Event event) {
		if (!this._deploy) {
			// ??????deploy????????
			logger.warn("package " + this._packageKey + " has undeployed");
			return false;
		}
		if (handler == null) {
			// ????handler????????????????????????????????
			for (Map.Entry<String, EventProcessor<?>> entry : this._processors.entrySet()) {
				EventProcessor processor = entry.getValue();
				try {
					processor.doEvent(event);
				} catch (Throwable ex) {
					logger.error(ex.getMessage(), ex);
				}
			}
			return true;
		}
		// ????????????????
		EventProcessor processor = this._processors.get(handler);
		if (processor == null || !processor.isStarted()) {
			logger.warn("context[" + this._packageKey + "],process[" + handler + "] not exist");
			return false;
		}
		try {
			return processor.doEvent(event);
		} catch (Throwable ex) {
			logger.error(ex.getMessage(), ex);
			return false;
		}
	}

	/**
	 * ????????????????????????
	 * ??????????????????????????
	 * ????????????????????????????????????????
	 */
	private class DefaultThreadFactory implements ThreadFactory {
		private final ThreadGroup _threadGroup;// ??????
		// ??????????????????????????????
		private final AtomicInteger threadNumber = new AtomicInteger(1);
		// ??????????????????????key+"-thread-"
		private final String namePrefix;

		public DefaultThreadFactory() {
			SecurityManager s = System.getSecurityManager();
			this._threadGroup = (s != null) ? s.getThreadGroup() : Thread
					.currentThread().getThreadGroup();
			this.namePrefix = _packageKey + "-thread-";
		}

		/**
		 * ????????
		 */
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(this._threadGroup, runnable, this.namePrefix
					+ this.threadNumber.getAndIncrement(), 0);
			if (thread.isDaemon())
				thread.setDaemon(false);
			if (thread.getPriority() != Thread.NORM_PRIORITY)
				thread.setPriority(Thread.NORM_PRIORITY);
			return thread;
		}
	}

}
