package io.ezeebit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import io.ezeebit.gateway.api.filter.MerchantPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.PredicateProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

// No merchant route can be reached without being charged. The only routes that are not merchant routes are the
// spec documents in DOC_ROUTES: uncharged by design, GET only, one literal path each, and none can match a merchant path.
class RouteCoverageIT {

	// The allow-list: route id -> the one literal path it serves. A route outside it must be a charged merchant route.
	static final Map<String, String> DOC_ROUTES = Map.of(
			"ledger-api-docs", "/v3/api-docs/ledger",
			"conversion-api-docs", "/v3/api-docs/conversion");

	private static final String MERCHANT = UUID.randomUUID().toString();

	private static RedisStore store;
	private static UpstreamStub upstream;
	private static ConfigurableApplicationContext gateway;

	@BeforeAll
	static void start() {
		store = RedisStore.started();
		upstream = UpstreamStub.start();
		gateway = Gateways.start(store.args(), upstream.instances());
	}

	@AfterAll
	static void stop() {
		gateway.close();
		upstream.close();
		store.stop();
	}

	@Test
	void everyRouteOutsideTheDocAllowListIsChargedToAMerchantBucket() {
		List<String> patterns = merchantRoutes().flatMap(route -> paths(route).stream()).toList();

		assertThat(patterns).as("the served table publishes the four merchant resources").hasSize(4);
		assertThat(patterns).allSatisfy(pattern -> assertThat(MerchantPath.of(concrete(pattern))).as(pattern)
				.isEqualTo(new MerchantPath(MerchantPath.Kind.MERCHANT, "merchant:%s".formatted(MERCHANT))));
	}

	@Test
	void everyDocRouteIsAllowListedAndServesOneLiteralPathToGetOnly() {
		Map<String, RouteProperties> docs = new TreeMap<>();
		routes().filter(route -> DOC_ROUTES.containsKey(route.getId())).forEach(route -> docs.put(route.getId(), route));

		assertThat(docs.keySet()).containsExactlyInAnyOrderElementsOf(DOC_ROUTES.keySet());
		docs.forEach((id, route) -> {
			assertThat(paths(route)).as(id).containsExactly(DOC_ROUTES.get(id));
			assertThat(predicate(route, "Method")).as(id).containsExactly("GET");
			assertThat(MerchantPath.of(DOC_ROUTES.get(id)).kind()).as(id).isEqualTo(MerchantPath.Kind.NOT_MERCHANT);
		});
	}

	// A literal pattern matches only itself, and none lies under /api/, so no merchant path can ever reach a doc route.
	@Test
	void noMerchantPathCanBeServedByADocRoute() {
		PathPatternParser parser = PathPatternParser.defaultInstance;
		List<String> merchantPatterns = merchantRoutes().flatMap(route -> paths(route).stream()).toList();
		List<String> docPaths = routes().filter(route -> DOC_ROUTES.containsKey(route.getId())).flatMap(route -> paths(route).stream())
				.toList();
		assertThat(docPaths).isNotEmpty();
		for (String doc : docPaths) {
			assertThat(doc).as("a doc route pattern must be a literal path").doesNotContain("*", "{", "?").doesNotStartWith("/api/");
			PathPattern docPattern = parser.parse(doc);
			for (String merchant : merchantPatterns) {
				assertThat(docPattern.matches(PathContainer.parsePath(concrete(merchant)))).as("%s serves %s", doc, merchant).isFalse();
				assertThat(parser.parse(merchant).matches(PathContainer.parsePath(doc))).as("%s serves %s", merchant, doc).isFalse();
			}
		}
	}

	private static Stream<RouteProperties> routes() {
		GatewayMvcProperties table = gateway.getBean(GatewayMvcProperties.class);
		return Stream.concat(table.getRoutes().stream(), table.getRoutesMap().values().stream());
	}

	private static Stream<RouteProperties> merchantRoutes() {
		return routes().filter(route -> !DOC_ROUTES.containsKey(route.getId()));
	}

	private static String concrete(final String pattern) {
		return pattern.replace("/**", "/x").replace("*", MERCHANT);
	}

	private static List<String> paths(final RouteProperties route) {
		route.getPredicates().forEach(predicate -> assertThat(predicate.getName())
				.as("route %s: only Path and Method predicates are understood here", route.getId()).isIn("Path", "Method"));
		return predicate(route, "Path");
	}

	private static List<String> predicate(final RouteProperties route, final String name) {
		List<String> values = new ArrayList<>();
		for (PredicateProperties predicate : route.getPredicates()) {
			if (predicate.getName().equals(name)) {
				predicate.getArgs().values().forEach(value -> values.addAll(List.of(value.split(","))));
			}
		}
		return values;
	}

}
