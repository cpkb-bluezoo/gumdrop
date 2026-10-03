#!/usr/bin/env python3
"""Check that each Gumdrop module jar's JPMS descriptor, exported packages and
Maven POM agree with what its classes really use.

``java --validate-modules`` only proves the descriptors resolve; it says
nothing about whether the code can read the modules it calls, so a module can
pass it and still fail with IllegalAccessError on the module path. This check
compares the real dependencies (from ``jdeps``) against:

  1. the module's ``requires`` (missing ones fail at run time on the module
     path; spurious ones are false boundaries between artifacts);
  2. its ``exports`` (a package that holds classes but is not exported is
     unusable from other modules);
  3. its Maven POM in central/ (a consumer who resolves the POM must get
     exactly the Gumdrop artifacts and libraries the module needs).

Run after ``ant jar`` (the ``jpms-check`` Ant target does both). Exits non-zero
and lists every problem if any check fails.
"""

from __future__ import annotations

import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
LIB = ROOT / "lib"
CENTRAL = ROOT / "central"

# Jars in lib/ that the build does not use (left over from earlier versions).
STALE_LIBS = ("agent15", "hkdf", "javax.servlet-api")

# jaxws-api cannot be resolved as a module (see UNRESOLVABLE_OPTIONAL).
UNRESOLVABLE_LIBS = ("jaxws-api",)

# Packages that hold classes but are deliberately not exported.
INTERNAL_PACKAGES = {
    # Launched reflectively by Bootstrap from the classpath, never by a module.
    "org.bluezoo.gumdrop.servlet.container",
}

# Third-party module name -> Maven artifactId.
THIRD_PARTY = {
    "org.bluezoo.gonzalez": "gonzalez-core",
    "org.bluezoo.json": "jsonparser",
    "org.bluezoo.protobuf": "jprotobuf",
    "org.bluezoo.micula": "micula",
    "jakarta.servlet": "jakarta.servlet-api",
}

# Optional libraries (``requires static``): the POM must list them as optional.
OPTIONAL_LIBS = {
    "jakarta.annotation": "jakarta.annotation-api",
    "jakarta.persistence": "jakarta.persistence-api",
    "java.annotation": "javax.annotation-api",
    "java.persistence": "javax.persistence-api",
    "jakarta.mail": "jakarta.mail-api",
    "javax.ejb.api": "javax.ejb-api",
}

# Libraries that only some code paths touch. A module that uses one must say
# so with ``requires static`` and mark the POM dependency optional.
OPTIONAL_PREFIXES = ("javax.", "jakarta.annotation", "jakarta.persistence",
                     "jakarta.mail", "java.xml.ws")

# Optional libraries that cannot be resolved as modules at all (jaxws-api's own
# descriptor requires java.xml.soap/java.xml.bind, which the JDK no longer has),
# so code that mentions them shows up as "not found" and stays out of the
# descriptor. Only the servlet container touches them, by name.
UNRESOLVABLE_OPTIONAL = ("javax.xml.ws", "javax.jws", "javax.xml.bind", "javax.xml.soap",
                         "javax.activation", "javax.transaction")

# Problems we know about and have chosen not to fix yet. They are reported on
# every run, with the reason, but do not fail the check. Remove an entry when
# the underlying issue is fixed. Each key is (artifact, package).
KNOWN_ISSUES = {
    ("gumdrop-servlet", "org.bluezoo.gumdrop"):
        "Context and ContextClassLoader use the container's bootstrap class "
        "loaders (ContainerClassLoader, DependencyClassLoader), which ship in "
        "gumdrop-container.jar and share a package with gumdrop-core. They are "
        "absent on a plain module path, so servlet hosting there needs the "
        "classloader coupling replaced by an interface.",
}

POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def describe(jar):
    """Return (name, requires, exports, contains) for a modular jar."""
    rc, out = run(["jar", "--describe-module", "--file", str(jar)])
    first = out.splitlines()[0].split()
    name = first[0].split("@")[0]
    requires = {}
    exports = set()
    contains = set()
    for line in out.splitlines()[1:]:
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "requires":
            mods = [x for x in parts[1:] if x not in ("transitive", "static", "mandated")]
            requires[mods[0].split("@")[0]] = {
                "transitive": "transitive" in parts,
                "static": "static" in parts,
                "mandated": "mandated" in parts,
            }
        elif parts[0] == "exports":
            exports.add(parts[1])
        elif parts[0] == "contains":
            contains.add(parts[1])
    opens = {}
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[0] == "opens":
            opens[parts[1]] = None                      # open to everyone
        elif len(parts) >= 4 and parts[0] == "qualified" and parts[1] == "opens":
            opens[parts[2]] = set(parts[4:])
    return name, requires, exports, contains, opens


def packages_with_classes(jar):
    import zipfile
    pk = set()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if (n.endswith(".class") and "/" in n and not n.startswith("META-INF")
                    and n != "module-info.class"):
                pk.add(n.rsplit("/", 1)[0].replace("/", "."))
    return pk


def packages_with_resources(jar):
    """Packages of every entry (classes and resource files) in a jar."""
    import zipfile
    pk = set()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if "/" in n and not n.startswith("META-INF") and not n.endswith("/"):
                pk.add(n.rsplit("/", 1)[0].replace("/", "."))
    return pk


def pom_dependencies(pom):
    """Return {artifactId: optional} for the POM's direct dependencies."""
    root = ET.parse(pom).getroot()
    deps = {}
    for d in root.findall("m:dependencies/m:dependency", POM_NS):
        aid = d.find("m:artifactId", POM_NS).text.strip()
        opt = d.find("m:optional", POM_NS)
        deps[aid] = opt is not None and opt.text.strip() == "true"
    return deps


def main():
    jars = sorted(p for p in DIST.glob("gumdrop-*.jar")
                  if not any(x in p.name for x in ("container", "sources", "javadoc")))
    if not jars:
        print("error: no module jars in dist/ - run 'ant jar' first", file=sys.stderr)
        return 2
    libs = sorted(p for p in LIB.glob("*.jar")
                  if not any(p.name.startswith(s) for s in STALE_LIBS + UNRESOLVABLE_LIBS))
    modpath = os.pathsep.join(str(p) for p in jars + libs)

    mods = {}      # module name -> info
    for jar in jars:
        name, requires, exports, contains, opens = describe(jar)
        mods[name] = {"jar": jar, "requires": requires, "exports": exports,
                      "opens": opens}
    artifact_of = {n: m["jar"].name[:-4] for n, m in mods.items()}
    errors = []
    known = set()

    # Resolve libs that are modules (or derivable automatic modules) so jdeps
    # reports them by name rather than "not found".
    lib_modules = []
    for lib in libs:
        rc, out = run(["jar", "--describe-module", "--file", str(lib)])
        first = out.splitlines()[0].split() if out.strip() else []
        if first and first[0] not in ("No", "releases:"):
            lib_modules.append(first[0].split("@")[0])
        elif "Derived automatic module" in out:
            m2 = re.search(r"^(\S+) automatic", out, re.M)
            if m2:
                lib_modules.append(m2.group(1).split("@")[0])
    roots = ",".join(["ALL-SYSTEM"] + sorted(mods) + lib_modules)
    jdeps_base = ["jdeps", "--multi-release", "25", "--module-path", modpath,
                  "--add-modules", roots]
    rc, out = run(jdeps_base + ["-summary"] + [str(m["jar"]) for m in mods.values()])
    uses = {n: set() for n in mods}
    for line in out.splitlines():
        mt = re.match(r"^(\S+)\s+->\s+(\S.*)$", line)
        if mt and mt.group(1) in uses:
            uses[mt.group(1)].add(mt.group(2).strip().split("@")[0])
    if not any(uses.values()):
        print("error: jdeps produced no output:\n" + out, file=sys.stderr)
        return 2

    for name, m in sorted(mods.items()):
        jar = m["jar"]
        short = artifact_of[name]
        # ---- real dependencies --------------------------------------
        used = {u for u in uses[name] if u not in ("java.base", name)}
        if "not found" in used:
            used.discard("not found")
            rc, vout = run(jdeps_base + ["-verbose:class", str(jar)])
            gone = set()
            own = packages_with_classes(jar)
            for line in vout.splitlines():
                # Only classes of this jar count: the other modules on the
                # analysis path (libraries included) have their own gaps.
                mt = re.match(r"^\s+(\S+)\s+(?:\(\S+\)\s+)?->\s+(\S+)\s+not found\s*$", line)
                if mt and mt.group(1).rsplit(".", 1)[0] in own:
                    gone.add(mt.group(2).rsplit(".", 1)[0])
            for pkg in sorted(gone):
                if pkg.startswith(UNRESOLVABLE_OPTIONAL):
                    continue
                if (short, pkg) in KNOWN_ISSUES:
                    known.add(f"{short}: classes use package {pkg}, which is on no module"
                              f"\n      {KNOWN_ISSUES[(short, pkg)]}")
                    continue
                errors.append(f"{short}: classes use package {pkg}, which is on no module")
        req = m["requires"]
        # modules readable via 'requires transitive' of what is declared
        readable = set(req)
        frontier = [r for r in req]
        while frontier:
            cur = frontier.pop()
            if cur in mods:
                for r2, f in mods[cur]["requires"].items():
                    if f["transitive"] and r2 not in readable:
                        readable.add(r2)
                        frontier.append(r2)
        # ---- 1. requires --------------------------------------------
        for u in sorted(used):
            if u not in readable:
                errors.append(f"{short}: classes use {u} but the descriptor does not require it")
        for r, f in sorted(req.items()):
            if f["mandated"]:
                continue
            if r not in used and not f["transitive"]:
                errors.append(f"{short}: requires {r} but no class uses it")
            if (r.startswith(OPTIONAL_PREFIXES) and not f["static"]):
                errors.append(f"{short}: {r} is an optional library and must be 'requires static'")
        # ---- 2. exports ---------------------------------------------
        for p in sorted(packages_with_classes(jar)):
            if p.startswith("org.bluezoo.gumdrop") and p not in m["exports"] \
                    and p not in INTERNAL_PACKAGES:
                errors.append(f"{short}: package {p} has classes but is not exported")
        # ---- 3. POM -------------------------------------------------
        pom = CENTRAL / f"{short}-pom.xml"
        if not pom.exists():
            errors.append(f"{short}: no {pom.relative_to(ROOT)}")
            continue
        deps = pom_dependencies(pom)
        want_gumdrop = {artifact_of[r] for r in req if r in mods and not req[r]["static"]}
        have_gumdrop = {a for a in deps if a.startswith("gumdrop-")}
        for a in sorted(want_gumdrop - have_gumdrop):
            errors.append(f"{short}: POM lacks dependency {a} (module requires it)")
        for a in sorted(have_gumdrop - want_gumdrop):
            errors.append(f"{short}: POM depends on {a} but the module does not require it")
        # third-party libraries: required unless supplied through another Gumdrop dep
        def closure(artifact):
            seen, todo = set(), [artifact]
            while todo:
                x = todo.pop()
                if x in seen:
                    continue
                seen.add(x)
                px = CENTRAL / f"{x}-pom.xml"
                if px.exists():
                    todo += [a for a in pom_dependencies(px) if a.startswith("gumdrop-")]
            return seen
        supplied = set()
        for a in want_gumdrop:
            for c in closure(a):
                px = CENTRAL / f"{c}-pom.xml"
                if px.exists():
                    supplied |= {d for d in pom_dependencies(px) if not d.startswith("gumdrop-")}
        for r, f in sorted(req.items()):
            if r in THIRD_PARTY:
                a = THIRD_PARTY[r]
                if a not in deps and a not in supplied:
                    errors.append(f"{short}: POM lacks library {a} (module requires {r})")
            elif r in OPTIONAL_LIBS:
                a = OPTIONAL_LIBS[r]
                if a not in deps:
                    errors.append(f"{short}: POM lacks optional library {a} (module requires static {r})")
                elif not deps[a]:
                    errors.append(f"{short}: POM must mark {a} optional (module requires static {r})")
            elif r not in mods and not r.startswith(("java.", "jdk.")):
                errors.append(f"{short}: no Maven mapping for required module {r}")
        have_lib = {a for a in deps if not a.startswith("gumdrop-")}
        want_lib = {THIRD_PARTY[r] for r in req if r in THIRD_PARTY}
        for a in sorted(have_lib - want_lib):
            if not deps[a]:     # optional libraries are allowed to be extra
                errors.append(f"{short}: POM depends on library {a} that the module does not require")

    # ---- 4. resource bundles ------------------------------------------
    # ResourceBundle.getBundle("x.L10N") finds only bundles in the caller's own
    # module. A class that names a bundle held by another module works on the
    # classpath and fails with MissingResourceException on the module path.
    pkg_module = {}
    for name, m in mods.items():
        for jar_pkg in packages_with_resources(m["jar"]):
            pkg_module.setdefault(jar_pkg, set()).add(artifact_of[name])
    for src in (ROOT / "src").rglob("*.java"):
        text = src.read_text(encoding="utf-8", errors="replace")
        rel = src.relative_to(ROOT / "src")
        caller_pkg = ".".join(rel.parts[:-1])
        for mt in re.finditer(r'getBundle\(\s*"([\w.]+)"\s*(,\s*[\w.]+\.class\.getModule\(\))?', text):
            bundle_pkg = mt.group(1).rsplit(".", 1)[0]
            caller_mods = pkg_module.get(caller_pkg)
            bundle_mods = pkg_module.get(bundle_pkg)
            if not (caller_mods and bundle_mods) or (caller_mods & bundle_mods):
                continue
            caller = sorted(caller_mods)[0]
            owner = sorted(bundle_mods)[0]
            if not mt.group(2):
                errors.append(
                    f"{caller}: {rel.as_posix()} loads bundle {mt.group(1)}, which is "
                    f"in {owner}; use getBundle(name, <class in {owner}>.class.getModule()) "
                    f"and open the package to {caller}")
                continue
            # module-aware lookup: the owning module must open the package to the caller
            caller_name = next(n for n, mm in mods.items() if artifact_of[n] == caller)
            owner_name = next(n for n, mm in mods.items() if artifact_of[n] == owner)
            to = mods[owner_name]["opens"].get(bundle_pkg, "missing")
            if to == "missing" or (to is not None and caller_name not in to):
                errors.append(
                    f"{caller}: {rel.as_posix()} loads bundle {mt.group(1)} from {owner}, "
                    f"but {owner} does not open {bundle_pkg} to {caller_name}")

    if known:
        print("Known issues (not failing the check):")
        for k in sorted(known):
            print("  * " + k)
    if errors:
        print("JPMS check FAILED (%d problems):" % len(errors))
        for e in sorted(set(errors)):
            print("  - " + e)
        return 1
    print("JPMS check passed: %d modules consistent with their classes and POMs" % len(mods))
    return 0


if __name__ == "__main__":
    sys.exit(main())
