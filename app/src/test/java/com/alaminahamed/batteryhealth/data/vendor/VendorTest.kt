package com.alaminahamed.batteryhealth.data.vendor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every expectation here is a real `Build.MANUFACTURER`/`Build.BRAND` pair as shipped,
 * not a tidied-up version of one. The casing is deliberately inconsistent between cases
 * because it is inconsistent on real hardware: Samsung reports lowercase `samsung`, Honor
 * reports uppercase `HONOR`, Oppo reports `OPPO`, and vivo reports lowercase `vivo`.
 */
class VendorTest {

    @Test
    fun manufacturerStringsFromRealDevicesResolve() {
        assertEquals(Vendor.Samsung, Vendor.of("samsung", "samsung"))
        assertEquals(Vendor.Google, Vendor.of("Google", "google"))
        assertEquals(Vendor.Xiaomi, Vendor.of("Xiaomi", "Xiaomi"))
        assertEquals(Vendor.OnePlus, Vendor.of("OnePlus", "OnePlus"))
        assertEquals(Vendor.Oppo, Vendor.of("OPPO", "OPPO"))
        assertEquals(Vendor.Vivo, Vendor.of("vivo", "vivo"))
        assertEquals(Vendor.Realme, Vendor.of("realme", "realme"))
        assertEquals(Vendor.Motorola, Vendor.of("motorola", "motorola"))
        assertEquals(Vendor.Nothing, Vendor.of("Nothing", "Nothing"))
        assertEquals(Vendor.Asus, Vendor.of("asus", "asus"))
        assertEquals(Vendor.Honor, Vendor.of("HONOR", "HONOR"))
        assertEquals(Vendor.Huawei, Vendor.of("HUAWEI", "HUAWEI"))
        assertEquals(Vendor.Sony, Vendor.of("Sony", "Sony"))
        assertEquals(Vendor.Hmd, Vendor.of("HMD Global", "Nokia"))
        assertEquals(Vendor.Lenovo, Vendor.of("Lenovo", "Lenovo"))
        assertEquals(Vendor.Zte, Vendor.of("ZTE", "ZTE"))
        assertEquals(Vendor.Tecno, Vendor.of("TECNO", "TECNO"))
        assertEquals(Vendor.Infinix, Vendor.of("INFINIX", "Infinix"))
        assertEquals(Vendor.Fairphone, Vendor.of("Fairphone", "Fairphone"))
    }

    /**
     * Redmi and POCO are Xiaomi sub-brands: they report `Build.MANUFACTURER` as `Xiaomi`
     * but `Build.BRAND` as their own name. Resolving on manufacturer alone already gets
     * these right, and this pins that a later brand-aware rule cannot accidentally split
     * them into vendors with no table of their own.
     */
    @Test
    fun xiaomiSubBrandsResolveToXiaomi() {
        assertEquals(Vendor.Xiaomi, Vendor.of("Xiaomi", "Redmi"))
        assertEquals(Vendor.Xiaomi, Vendor.of("Xiaomi", "POCO"))
    }

    /**
     * iQOO ships with `Build.MANUFACTURER` = `vivo`. Same reasoning as the Xiaomi
     * sub-brands above.
     */
    @Test
    fun iqooResolvesToVivo() {
        assertEquals(Vendor.Vivo, Vendor.of("vivo", "iQOO"))
    }

    /**
     * Some manufacturers ship the long legal entity name rather than the brand:
     * Infinix and Tecno both do this, and Motorola has shipped `Motorola Mobility LLC`
     * under Lenovo ownership. Prefix matching handles all three; exact matching would
     * drop them to [Vendor.Unknown] and silently disable every vendor rule for them.
     */
    @Test
    fun legalEntityNamesResolveToTheBrand() {
        assertEquals(Vendor.Infinix, Vendor.of("Infinix Mobility Limited", "Infinix"))
        assertEquals(Vendor.Tecno, Vendor.of("TECNO MOBILE LIMITED", "TECNO"))
        assertEquals(Vendor.Motorola, Vendor.of("Motorola Mobility LLC", "motorola"))
    }

    /**
     * An unrecognised manufacturer is [Vendor.Unknown], never a nearest guess. A vendor
     * rule applied to the wrong vendor is how this app would start reporting one phone's
     * battery figures for another, which is the failure mode the whole codebase is built
     * to avoid.
     */
    @Test
    fun anUnrecognisedManufacturerIsUnknown() {
        assertEquals(Vendor.Unknown, Vendor.of("Acme Phones Ltd", "Acme"))
        assertEquals(Vendor.Unknown, Vendor.of("", ""))
        assertEquals(Vendor.Unknown, Vendor.of("   ", "   "))
    }

    /**
     * `Vendor.of` must not match a vendor token that merely appears somewhere inside a
     * longer unrelated string. `startsWith` is the rule, not `contains`: a hypothetical
     * `NotSamsung` or `MyHonorPhone` is not the vendor it names.
     */
    @Test
    fun aVendorTokenInsideAnUnrelatedStringDoesNotMatch() {
        assertEquals(Vendor.Unknown, Vendor.of("NotSamsung", "NotSamsung"))
        assertEquals(Vendor.Unknown, Vendor.of("MyHonorPhone", "MyHonorPhone"))
    }

    /**
     * Every vendor except [Vendor.Unknown] must be reachable from at least one
     * manufacturer token, driven off `entries` so adding a vendor without giving it a
     * token fails here rather than shipping a vendor nothing can ever resolve to.
     */
    @Test
    fun everyVendorIsReachableFromSomeManufacturerToken() {
        val unreachable = Vendor.entries
            .filter { it != Vendor.Unknown }
            .filter { vendor -> vendor.manufacturerTokens.isEmpty() }
        assertEquals(emptyList<Vendor>(), unreachable)

        Vendor.entries.filter { it != Vendor.Unknown }.forEach { vendor ->
            vendor.manufacturerTokens.forEach { token ->
                assertEquals("token '$token'", vendor, Vendor.of(token, token))
            }
        }
    }

    /**
     * No two vendors may claim the same manufacturer token, and no token may be a prefix
     * of another vendor's token. Either would make [Vendor.of] order-dependent, which is
     * exactly the kind of silently-wrong-vendor bug the table exists to prevent.
     */
    @Test
    fun manufacturerTokensAreUnambiguousAcrossVendors() {
        val owned = Vendor.entries
            .filter { it != Vendor.Unknown }
            .flatMap { vendor -> vendor.manufacturerTokens.map { it to vendor } }

        val duplicates = owned.groupBy { it.first }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicates)

        val prefixCollisions = owned.flatMap { (token, vendor) ->
            owned.filter { (other, otherVendor) ->
                otherVendor != vendor && other.startsWith(token)
            }.map { (other, otherVendor) -> "$token ($vendor) shadows $other ($otherVendor)" }
        }
        assertEquals(emptyList<String>(), prefixCollisions)
    }
}
