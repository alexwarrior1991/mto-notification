package com.alejandro.mtonotification.configuration.scheduling;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * El planificador de los {@code @Scheduled}: un pool de {@code spring.task.scheduling.pool.size} (4)
 * hilos, virtuales con {@code spring.threads.virtual.enabled}, para que ninguna pasada espere a otra.
 *
 * <p>El lector de Keycloak, el cierre de rafagas y el despacho de entregas son {@code fixedDelay}, y la
 * purga, un cron. Con los hilos virtuales, Boot planifica con un {@code SimpleAsyncTaskScheduler}, que
 * corre todas las tareas {@code fixedDelay} en su unico hilo de planificacion: un Keycloak lento (hasta
 * los 15 s del circuito por pagina) o un SMTP lento paraban el cierre de rafagas y los reintentos del
 * correo. Sin ellos, Boot usa un {@code ThreadPoolTaskScheduler} de un hilo si nadie fija el tamano. Aqui
 * el pool es el de siempre en los dos casos: cada tarea sigue sin solaparse consigo misma (el siguiente
 * {@code fixedDelay} se cuenta desde que termina la pasada anterior), pero las demas no la esperan.</p>
 *
 * <p>El planificador no es un bean a proposito: un {@code ThreadPoolTaskScheduler} es tambien un
 * {@code Executor}, y un {@code Executor} en el contexto haria que Boot dejara de crear su
 * {@code applicationTaskExecutor} (lo mismo que en {@code KeycloakCircuitBreakerExecutorConfiguration}).
 * Boot sigue creando su {@code taskScheduler}, en el que ya no se planifica nada. Este se cierra con el
 * contexto.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler scheduler;

    public SchedulingConfiguration(ThreadPoolTaskSchedulerBuilder builder, Environment environment) {
        ThreadPoolTaskScheduler built = builder.build();
        if (Threading.VIRTUAL.isActive(environment)) {
            built.setThreadFactory(Thread.ofVirtual().name(built.getThreadNamePrefix(), 0).factory());
        }
        built.initialize();
        this.scheduler = built;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.destroy();
    }
}
