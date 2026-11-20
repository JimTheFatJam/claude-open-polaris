package io.github.jimthefatjam.polaris.core.astro

import io.github.jimthefatjam.polaris.core.model.CelestialObject
import io.github.jimthefatjam.polaris.core.model.ObjectType
import io.github.jimthefatjam.polaris.core.model.ObjectType.DOUBLE_STAR
import io.github.jimthefatjam.polaris.core.model.ObjectType.GALAXY
import io.github.jimthefatjam.polaris.core.model.ObjectType.GLOBULAR_CLUSTER
import io.github.jimthefatjam.polaris.core.model.ObjectType.NEBULA
import io.github.jimthefatjam.polaris.core.model.ObjectType.OPEN_CLUSTER
import io.github.jimthefatjam.polaris.core.model.ObjectType.PLANETARY_NEBULA
import io.github.jimthefatjam.polaris.core.model.ObjectType.STAR

/**
 * Built-in catalog. Coordinates are J2000.0 (RA in decimal hours, Dec in decimal degrees) taken from
 * the Yale Bright Star Catalogue / SIMBAD for stars and the SEDS Messier/NGC database for deep-sky
 * objects, rounded to ~0.01°, which is far below what any phone-based pointing can resolve.
 * Magnitudes are visual (V); for extended objects they are integrated magnitudes.
 */
object Catalog {

    val sun = CelestialObject(
        "sun", "Sun", ObjectType.SUN, -26.7,
        description = "Our star. Never observe it through optics without a certified solar filter.",
    )
    val moon = CelestialObject(
        "moon", "Moon", ObjectType.MOON, -12.7,
        description = "Best observed near the terminator (the day/night line), where shadows reveal relief.",
    )

    val planets: List<CelestialObject> = listOf(
        planet("mercury", "Mercury", -0.4, "Always close to the Sun; look low in twilight."),
        planet("venus", "Venus", -4.2, "Shows phases like the Moon. Brilliant in twilight."),
        planet("mars", "Mars", 0.7, "Surface detail needs steady seeing and a close approach (opposition)."),
        planet("jupiter", "Jupiter", -2.2, "Cloud belts, the Great Red Spot and four Galilean moons."),
        planet("saturn", "Saturn", 0.6, "The rings are visible in almost any telescope."),
        planet("uranus", "Uranus", 5.7, "A small blue-green disk; at the naked-eye limit under dark skies."),
        planet("neptune", "Neptune", 7.8, "Needs a telescope and a finder chart."),
    )

    val stars: List<CelestialObject> = listOf(
        star("sirius", "Sirius", 6.7525, -16.7161, -1.46, "CMa", "α CMa", "Brightest star in the night sky."),
        star("canopus", "Canopus", 6.3992, -52.6957, -0.74, "Car", "α Car", "Second-brightest star; southern sky."),
        star("rigil", "Rigil Kentaurus", 14.6600, -60.8340, -0.27, "Cen", "α Cen", "Nearest star system to the Sun."),
        star("arcturus", "Arcturus", 14.2610, 19.1824, -0.05, "Boo", "α Boo", "Orange giant; follow the arc of the Big Dipper's handle."),
        star("vega", "Vega", 18.6156, 38.7837, 0.03, "Lyr", "α Lyr", "Corner of the Summer Triangle."),
        star("capella", "Capella", 5.2782, 45.9980, 0.08, "Aur", "α Aur", "Yellow star high in northern winter skies."),
        star("rigel", "Rigel", 5.2423, -8.2016, 0.13, "Ori", "β Ori", "Blue supergiant, Orion's foot."),
        star("procyon", "Procyon", 7.6550, 5.2250, 0.34, "CMi", "α CMi", "Part of the Winter Triangle."),
        star("achernar", "Achernar", 1.6286, -57.2368, 0.46, "Eri", "α Eri", "End of the river Eridanus."),
        star("betelgeuse", "Betelgeuse", 5.9195, 7.4071, 0.50, "Ori", "α Ori", "Red supergiant; brightness varies."),
        star("hadar", "Hadar", 14.0637, -60.3730, 0.61, "Cen", "β Cen", "Pointer to the Southern Cross."),
        star("altair", "Altair", 19.8464, 8.8683, 0.76, "Aql", "α Aql", "Corner of the Summer Triangle."),
        star("acrux", "Acrux", 12.4433, -63.0991, 0.76, "Cru", "α Cru", "Foot of the Southern Cross; a fine double."),
        star("aldebaran", "Aldebaran", 4.5987, 16.5093, 0.86, "Tau", "α Tau", "Orange eye of the Bull."),
        star("spica", "Spica", 13.4199, -11.1613, 0.97, "Vir", "α Vir", "Blue-white star of Virgo."),
        star("antares", "Antares", 16.4901, -26.4320, 1.06, "Sco", "α Sco", "Red heart of the Scorpion."),
        star("pollux", "Pollux", 7.7553, 28.0262, 1.14, "Gem", "β Gem", "Brighter of the Gemini twins."),
        star("fomalhaut", "Fomalhaut", 22.9608, -29.6222, 1.16, "PsA", "α PsA", "Lonely bright star of autumn."),
        star("deneb", "Deneb", 20.6905, 45.2803, 1.25, "Cyg", "α Cyg", "Tail of the Swan; Summer Triangle."),
        star("mimosa", "Mimosa", 12.7953, -59.6888, 1.25, "Cru", "β Cru", "Southern Cross."),
        star("regulus", "Regulus", 10.1395, 11.9672, 1.36, "Leo", "α Leo", "Heart of the Lion."),
        star("adhara", "Adhara", 6.9771, -28.9721, 1.50, "CMa", "ε CMa", "Canis Major."),
        star("castor", "Castor", 7.5767, 31.8883, 1.58, "Gem", "α Gem", "A multiple star; splits in small telescopes."),
        star("shaula", "Shaula", 17.5601, -37.1038, 1.62, "Sco", "λ Sco", "Scorpion's sting."),
        star("bellatrix", "Bellatrix", 5.4189, 6.3497, 1.64, "Ori", "γ Ori", "Orion's shoulder."),
        star("alnilam", "Alnilam", 5.6036, -1.2019, 1.69, "Ori", "ε Ori", "Centre of Orion's Belt."),
        star("dubhe", "Dubhe", 11.0621, 61.7510, 1.79, "UMa", "α UMa", "Pointer star of the Big Dipper."),
        star("mirfak", "Mirfak", 3.4054, 49.8612, 1.79, "Per", "α Per", "Within the Alpha Persei cluster."),
        star("alkaid", "Alkaid", 13.7923, 49.3133, 1.86, "UMa", "η UMa", "End of the Big Dipper's handle."),
        star("polaris", "Polaris", 2.5302, 89.2641, 1.98, "UMi", "α UMi", "The North Star, within 1° of the celestial pole. Useful for polar alignment."),
        star("alphard", "Alphard", 9.4598, -8.6586, 1.98, "Hya", "α Hya", "The solitary one in Hydra."),
        star("hamal", "Hamal", 2.1196, 23.4624, 2.00, "Ari", "α Ari", "Brightest star in Aries."),
        star("algol", "Algol", 3.1361, 40.9556, 2.1, "Per", "β Per", "Eclipsing binary: dims every 2.87 days."),
        CelestialObject("mizar", "Mizar & Alcor", DOUBLE_STAR, 2.23, 13.3988, 54.9254, "UMa", listOf("ζ UMa"),
            "Naked-eye pair; Mizar itself splits at low power."),
        CelestialObject("albireo", "Albireo", DOUBLE_STAR, 3.05, 19.5121, 27.9597, "Cyg", listOf("β Cyg"),
            "Gold and blue double — a showpiece at low power."),
    )

    val deepSky: List<CelestialObject> = listOf(
        dso("m31", "Andromeda Galaxy", GALAXY, 0.7123, 41.2692, 3.4, "And", "M31", 178.0, "Nearest large spiral galaxy; naked-eye under dark skies."),
        dso("m33", "Triangulum Galaxy", GALAXY, 1.5641, 30.6602, 5.7, "Tri", "M33", 70.0, "Large, very low surface brightness; needs a dark, moonless sky."),
        dso("m45", "Pleiades", OPEN_CLUSTER, 3.7833, 24.1167, 1.6, "Tau", "M45", 110.0, "The Seven Sisters; best in binoculars."),
        dso("hyades", "Hyades", OPEN_CLUSTER, 4.4500, 15.8700, 0.5, "Tau", "Mel 25", 330.0, "V-shaped cluster around Aldebaran."),
        dso("double_cluster", "Double Cluster", OPEN_CLUSTER, 2.3333, 57.1333, 3.7, "Per", "NGC 869/884", 60.0, "Two rich clusters in one low-power field."),
        dso("m42", "Orion Nebula", NEBULA, 5.5881, -5.3911, 4.0, "Ori", "M42", 85.0, "Brightest nebula; the Trapezium sits in its core."),
        dso("m1", "Crab Nebula", NEBULA, 5.5755, 22.0145, 8.4, "Tau", "M1", 6.0, "Supernova remnant from AD 1054."),
        dso("m35", "M35", OPEN_CLUSTER, 6.1483, 24.3333, 5.3, "Gem", "M35", 28.0, "Rich cluster at Castor's feet."),
        dso("m41", "M41", OPEN_CLUSTER, 6.7667, -20.7333, 4.5, "CMa", "M41", 38.0, "Bright cluster just south of Sirius."),
        dso("m44", "Beehive Cluster", OPEN_CLUSTER, 8.6667, 19.6667, 3.7, "Cnc", "M44", 95.0, "Large cluster; ideal for binoculars."),
        dso("m81", "Bode's Galaxy", GALAXY, 9.9259, 69.0653, 6.9, "UMa", "M81", 27.0, "Bright spiral; pairs with M82."),
        dso("m82", "Cigar Galaxy", GALAXY, 9.9318, 69.6797, 8.4, "UMa", "M82", 11.0, "Edge-on starburst galaxy near M81."),
        dso("carina", "Carina Nebula", NEBULA, 10.7525, -59.8667, 1.0, "Car", "NGC 3372", 120.0, "Vast southern nebula around Eta Carinae."),
        dso("m104", "Sombrero Galaxy", GALAXY, 12.6664, -11.6231, 8.0, "Vir", "M104", 9.0, "Edge-on galaxy with a dark dust lane."),
        dso("omega_cen", "Omega Centauri", GLOBULAR_CLUSTER, 13.4462, -47.4794, 3.9, "Cen", "NGC 5139", 36.0, "Largest globular cluster in the Milky Way."),
        dso("m51", "Whirlpool Galaxy", GALAXY, 13.4980, 47.1953, 8.4, "CVn", "M51", 11.0, "Face-on spiral with a companion; spiral arms need dark skies."),
        dso("m3", "M3", GLOBULAR_CLUSTER, 13.7034, 28.3772, 6.2, "CVn", "M3", 18.0, "Bright globular cluster."),
        dso("m101", "Pinwheel Galaxy", GALAXY, 14.0535, 54.3488, 7.9, "UMa", "M101", 29.0, "Large face-on spiral, low surface brightness."),
        dso("m5", "M5", GLOBULAR_CLUSTER, 15.3092, 2.0811, 5.6, "Ser", "M5", 23.0, "One of the finest northern globulars."),
        dso("m4", "M4", GLOBULAR_CLUSTER, 16.3931, -26.5258, 5.6, "Sco", "M4", 36.0, "Loose globular next to Antares."),
        dso("m13", "Hercules Cluster", GLOBULAR_CLUSTER, 16.6949, 36.4613, 5.8, "Her", "M13", 20.0, "Great globular cluster in Hercules."),
        dso("m92", "M92", GLOBULAR_CLUSTER, 17.2853, 43.1359, 6.4, "Her", "M92", 14.0, "Often overlooked neighbour of M13."),
        dso("m6", "Butterfly Cluster", OPEN_CLUSTER, 17.6683, -32.2533, 4.2, "Sco", "M6", 25.0, "Butterfly-shaped open cluster."),
        dso("m7", "Ptolemy Cluster", OPEN_CLUSTER, 17.8975, -34.7928, 3.3, "Sco", "M7", 80.0, "Bright naked-eye cluster near the Scorpion's tail."),
        dso("m20", "Trifid Nebula", NEBULA, 18.0436, -22.9717, 6.3, "Sgr", "M20", 28.0, "Emission nebula split by dark lanes."),
        dso("m8", "Lagoon Nebula", NEBULA, 18.0606, -24.3867, 6.0, "Sgr", "M8", 90.0, "Bright nebula visible to the naked eye from dark sites."),
        dso("m17", "Omega Nebula", NEBULA, 18.3433, -16.1717, 6.0, "Sgr", "M17", 11.0, "Also called the Swan Nebula."),
        dso("m22", "M22", GLOBULAR_CLUSTER, 18.6067, -23.9047, 5.1, "Sgr", "M22", 32.0, "Bright, nearby globular in Sagittarius."),
        dso("m11", "Wild Duck Cluster", OPEN_CLUSTER, 18.8511, -6.2717, 5.8, "Sct", "M11", 14.0, "Dense, rich open cluster."),
        dso("m57", "Ring Nebula", PLANETARY_NEBULA, 18.8931, 33.0292, 8.8, "Lyr", "M57", 1.4, "Small smoke ring; tolerates moderate light pollution."),
        dso("m27", "Dumbbell Nebula", PLANETARY_NEBULA, 19.9934, 22.7212, 7.5, "Vul", "M27", 8.0, "Bright, large planetary nebula."),
        dso("m15", "M15", GLOBULAR_CLUSTER, 21.4999, 12.1670, 6.2, "Peg", "M15", 18.0, "Dense-cored globular in Pegasus."),
        dso("m2", "M2", GLOBULAR_CLUSTER, 21.5575, -0.8233, 6.5, "Aqr", "M2", 16.0, "Rich globular in Aquarius."),
        dso("ngc7000", "North America Nebula", NEBULA, 20.9833, 44.3333, 4.0, "Cyg", "NGC 7000", 120.0, "Very large and faint; best with binoculars and dark skies."),
        dso("tuc47", "47 Tucanae", GLOBULAR_CLUSTER, 0.4014, -72.0814, 4.1, "Tuc", "NGC 104", 31.0, "Spectacular southern globular cluster."),
        dso("lmc", "Large Magellanic Cloud", GALAXY, 5.3917, -69.7561, 0.9, "Dor", "LMC", 645.0, "Satellite galaxy; naked-eye from the southern hemisphere."),
        dso("smc", "Small Magellanic Cloud", GALAXY, 0.8772, -72.8003, 2.7, "Tuc", "SMC", 320.0, "Satellite galaxy near 47 Tucanae."),
    )

    val all: List<CelestialObject> = listOf(sun, moon) + planets + stars + deepSky

    /** Objects that can be used as an alignment reference for the Telescope screen (bright, point-like). */
    val alignmentCandidates: List<CelestialObject> =
        listOf(moon) + planets.filter { (it.magnitude ?: 99.0) < 2.0 } + stars.filter { (it.magnitude ?: 99.0) < 2.1 }

    fun byId(id: String): CelestialObject? = all.firstOrNull { it.id == id }

    fun search(query: String): List<CelestialObject> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { o ->
            o.name.lowercase().contains(q) ||
                o.catalogIds.any { it.lowercase().replace(" ", "").contains(q.replace(" ", "")) } ||
                (o.constellation?.lowercase() == q) ||
                o.type.label.lowercase().contains(q)
        }
    }

    private fun planet(id: String, name: String, mag: Double, desc: String) =
        CelestialObject(id, name, ObjectType.PLANET, mag, description = desc)

    private fun star(id: String, name: String, ra: Double, dec: Double, mag: Double, con: String, bayer: String, desc: String) =
        CelestialObject(id, name, STAR, mag, ra, dec, con, listOf(bayer), desc)

    private fun dso(
        id: String, name: String, type: ObjectType, ra: Double, dec: Double, mag: Double,
        con: String, cat: String, size: Double, desc: String,
    ) = CelestialObject(id, name, type, mag, ra, dec, con, listOf(cat), desc, size)
}
