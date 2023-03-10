package com.example.iot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.ip.IpHeaders;
import org.springframework.integration.ip.dsl.Tcp;
import org.springframework.integration.ip.tcp.connection.AbstractServerConnectionFactory;
import org.springframework.integration.ip.tcp.connection.TcpConnectionServerListeningEvent;
import org.springframework.integration.ip.tcp.serializer.ByteArrayCrLfSerializer;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@SpringBootApplication
public class IotApplication {
	private static final int PORT = 1234;
	@Configuration
	public static class Config {

		private final CountDownLatch listenLatch = new CountDownLatch(1);

		/*
		 * Server connection factory.
		 */
		@Bean
		public AbstractServerConnectionFactory serverFactory() {
			return Tcp.netServer(PORT).get();
		}

		/*
		 * Inbound adapter - sends "connected!".
		 */
		@Bean
		public IntegrationFlow tcpServer(AbstractServerConnectionFactory serverFactory) {
			return IntegrationFlows.from(Tcp.inboundAdapter(serverFactory))
					.transform(p -> "connected!")
					.channel("toTcp.input")
					.get();
		}

		/*
		 * Gateway flow for controller.
		 */
		@Bean
		public IntegrationFlow gateway() {
			return IntegrationFlows.from(Sender.class)
					.channel("toTcp.input")
					.get();
		}

		/*
		 * Outbound channel adapter flow.
		 */
		@Bean
		public IntegrationFlow toTcp(AbstractServerConnectionFactory serverFactory) {
			return f -> f.handle(Tcp.outboundAdapter(serverFactory));
		}

		/*
		 * Excutor for clients.
		 */
		@Bean
		public ThreadPoolTaskExecutor exec() {
			ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
			exec.setCorePoolSize(5);
			return exec;
		}

		/*
		 * Wait for server to start listenng and start 5 clients.
		 */
		@Bean
		public ApplicationRunner runner(TaskExecutor exec, Broadcaster caster) {
			return args -> {
				if (!this.listenLatch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Failed to start listening");
				}
				IntStream.range(1, 6).forEach(i -> exec.execute(new Client()));
			};
		}

		@EventListener
		public void serverStarted(TcpConnectionServerListeningEvent event) {
			this.listenLatch.countDown();
		}

	}
	public interface Sender {

		void send(String payload, @Header(IpHeaders.CONNECTION_ID) String connectionId);

	}
	@RestController
	public static class Controller {

		@Autowired
		private Broadcaster broadcaster;

		@Autowired
		private ConfigurableApplicationContext applicationContext;

		@PostMapping("/broadcast/{what}")
		public String broadcast(@PathVariable String what) {
			this.broadcaster.send(what);
			return "sent: " + what;
		}
		@GetMapping("/broadcast-g/{what}")
		public String broadcastG(@PathVariable String what) {
			this.broadcaster.send(what);
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return "sent-g: " + what;
		}
		@PostMapping ("/broadcast-byte")
		public String broadcastByte(@RequestBody byte [] what) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < what.length; i++) {
				sb.append(String.format("%02X ", what[i]));
			}
			this.broadcaster.send( sb.toString());
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return "sent-g: " + sb.toString();
		}
		@RequestMapping("/shutdown")
		public void shutDown() {
			this.applicationContext.close();
		}
	}
	@Component
	@DependsOn("gateway") // Needed to ensure the gateway flow bean is created first
	public static class Broadcaster {

		@Autowired
		private Sender sender;

		@Autowired
		private AbstractServerConnectionFactory server;

		public void send(String what) {
			this.server.getOpenConnectionIds().forEach(cid -> sender.send(what, cid));
		}

	}

	public static class Client implements Runnable {

		private static final ByteArrayCrLfSerializer deserializer = new ByteArrayCrLfSerializer();

		private static int next;

		private final int instance = ++next;

		@Override
		public void run() {
			Socket socket = null;
			try {
				socket = SocketFactory.getDefault().createSocket("localhost", PORT);
				socket.getOutputStream().write("hello\r\n".getBytes());
				InputStream is = socket.getInputStream();
				while(true) {
					System.out.println(new String(deserializer.deserialize(is)) + " from client# " + instance);
				}
			}
			catch (IOException e) {
			}
			finally {
				if (socket != null) {
					try {
						socket.close();
					}
					catch (IOException e) {
					}
				}
			}
		}

	}

	public static void main(String[] args) {
		SpringApplication.run(IotApplication.class, args);
	}

}
