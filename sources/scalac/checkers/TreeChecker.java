/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.checkers;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import scalac.Unit;
import scalac.ast.Tree;
import scalac.symtab.Definitions;
import scalac.symtab.Symbol;
import scalac.util.Debug;

/**
 * This checker checks that trees are well-formed. It checks both the
 * shape and the attribution of the trees.
 */
public class TreeChecker {

    //########################################################################
    // Private Fields

    /** The global definitions */
    private final Definitions definitions;

    /** The stack of current units */
    private final Stack/*<Unit>*/ units = new Stack();

    /** The stack of current classes */
    private final Stack/*<Symbol>*/ classes = new Stack();

    /** The stack of current members */
    private final Stack/*<Symbol>*/ members = new Stack();

    /** The stack of current owners */
    private final Stack/*<Symbol>*/ owners = new Stack();

    /** The currently visible type variables */
    private final Set/*<Symbol>*/ tvars = new HashSet();

    /** The currently visible value variables */
    private final Set/*<Symbol>*/ vvars = new HashSet();

    /** The currently visible labels */
    private final Set/*<Symbol>*/ labels = new HashSet();

    /** The currently defined symbols */
    private final Set/*<Symbol>*/ symbols = new HashSet();

    //########################################################################
    // Public Constructors

    /** Initializes this instance. */
    public TreeChecker(Definitions definitions) {
        this.definitions = definitions;
    }

    //########################################################################
    // Public Methods - Checking units

    /** Checks the unit. Returns true. */
    public boolean check(Unit unit) {
        return true;
    }

    //########################################################################
    // Private Methods - Declaring symbols

    /** Do the trees contain the given symbols? */
    private boolean containSymbols(Tree[] trees, Symbol[] symbols) {
        if (trees.length != symbols.length) return false;
        for (int i = 0; i < trees.length; i++)
            if (trees[i].symbol() != symbols[i]) return false;
        return true;
    }

    /** Remove parameters of symbol from current scope. */
    private void scopeInsertParametersOf(Symbol symbol) {
        Symbol[] tparams = symbol.typeParams();
        for (int i = 0; i < tparams.length; i++)
            scopeInsertTVariable(tparams[i], true);
        Symbol[] vparams = symbol.valueParams();
        for (int i = 0; i < vparams.length; i++)
            scopeInsertVVariable(vparams[i], true);
    }

    /** Adds the type variable to the current scope. */
    private void scopeInsertTVariable(Symbol symbol, boolean isParameter) {
        assert !symbol.owner().isClass(): show(symbol);
        assert symbol.isParameter() == isParameter: show(symbol);
        registerSymbol(symbol);
        tvars.add(symbol);
    }

    /** Adds the value variable to the current scope. */
    private void scopeInsertVVariable(Symbol symbol, boolean isParameter) {
        assert !symbol.owner().isClass(): show(symbol);
        assert symbol.isParameter() == isParameter: show(symbol);
        registerSymbol(symbol);
        vvars.add(symbol);
    }

    /** Adds the label to current scope. */
    private void scopeInsertLabel(Symbol symbol) {
        assert !symbol.owner().isClass(): show(symbol);
        registerSymbol(symbol);
        labels.add(symbol);
    }

    /** Remove parameters of symbol from current scope. */
    private void scopeRemoveParametersOf(Symbol symbol) {
        Symbol[] tparams = symbol.typeParams();
        for (int i = 0; i < tparams.length; i++)
            scopeRemoveTVariable(tparams[i]);
        Symbol[] vparams = symbol.valueParams();
        for (int i = 0; i < vparams.length; i++)
            scopeRemoveVVariable(vparams[i]);
    }

    /** Removes the type variable from current scope. */
    private void scopeRemoveTVariable(Symbol symbol) {
        boolean removed = tvars.remove(symbol);
        assert removed: show(symbol);
    }

    /** Removes the value variable from current scope. */
    private void scopeRemoveVVariable(Symbol symbol) {
        boolean removed = vvars.remove(symbol);
        assert removed: show(symbol);
    }

    /** Removes the label symbol from current scope. */
    private void scopeRemoveLabel(Symbol symbol) {
        boolean removed = labels.remove(symbol);
        assert removed: show(symbol);
    }

    /** Registers the symbol. */
    private void registerSymbol(Symbol symbol) {
        boolean added = symbols.add(symbol);
        assert added: show(symbol);
    }

    //########################################################################
    // Private Methods - Managing current context

    /** Returns the current unit. */
    private Unit currentUnit() {
        return units.size() > 0 ? (Unit)units.peek() : null;
    }

    /** Returns the current class. */
    private Symbol currentClass() {
        return classes.size() > 0 ? (Symbol)classes.peek() : null;
    }

    /** Returns the current member. */
    private Symbol currentMember() {
        return members.size() > 0 ? (Symbol)members.peek() : null;
    }

    /** Returns the current owner. */
    private Symbol currentOwner() {
        return owners.size() > 0 ? (Symbol)owners.peek() : null;
    }

    /** Sets the current unit to the given one. */
    private void pushUnit(Unit unit) {
        assert units.size() == 0: showPush(unit);
        assert classes.size() == 0: showPush(unit);
        assert members.size() == 0: showPush(unit);
        units.push(unit);
    }

    /** Sets the current class to the given one. */
    private void pushClass(Symbol clasz) {
        assert clasz.isClass(): showPush(clasz);
        assert members.size() == 0: showPush(clasz);
        classes.push(clasz);
        pushOwner(clasz);
    }

    /** Sets the current member to the given one. */
    private void pushMember(Symbol member) {
        assert member.isTerm(): showPush(member);
        assert members.size() == 0: showPush(member);
        assert member.owner() == currentClass(): showPush(member);
        members.push(member);
        pushOwner(member);
    }

    /** Sets the current owner to the given one. */
    private void pushOwner(Symbol owner) {
        owners.push(owner);
    }

    /** Sets the current unit to the previous one. */
    private void popUnit() {
        assert units.size() > 0: show();
        units.pop();
    }

    /** Sets the current class to the previous one. */
    private void popClass() {
        assert members.size() == 0: show();
        assert classes.size() > 0: show();
        popOwner();
        classes.pop();
    }

    /** Sets the current member to the previous one. */
    private void popMember() {
        assert members.size() > 0: show();
        popOwner();
        members.pop();
    }

    /** Sets the current owner to the previous one. */
    private void popOwner() {
        assert owners.size() > 0: show();
        owners.pop();
    }

    //########################################################################
    // Private Methods - Showing errors

    /** Returns the current position. */
    private String show() {
        return
            format("unit", currentUnit()) +
            format("clasz", currentClass()) +
            format("member", currentMember()) +
            format("owner", currentOwner());
    }

    /** Returns the current position and given header and value. */
    private String show(String header, Object value) {
        return show() + format(header, value);
    }

    /** Returns the current position and given symbol. */
    private String show(Symbol symbol) {
        return show("symbol", symbol);
    }

    /** Returns the current position and given pushed value. */
    private String showPush(Object value) {
        return show("pushing", value);
    }

    /** Returns a string with the given header and value. */
    private String format(String header, Object value) {
        while (header.length() < 10) header = header + ' ';
        return "\n" + header + ": " + toString(value);
    }

    /** Returns a string representation of the given value. */
    private String toString(Object value) {
        if (value instanceof Symbol) return Debug.show(value);
        return String.valueOf(value);
    }

    //########################################################################
}
