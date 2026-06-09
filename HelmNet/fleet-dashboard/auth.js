import {
    getAuth,
    signInWithPopup,
    GoogleAuthProvider,
    onAuthStateChanged,
    signOut
} from 'https://www.gstatic.com/firebasejs/10.12.0/firebase-auth.js';

let _auth = null;

export function initAuth(app) {
    _auth = getAuth(app);
    return _auth;
}

/**
 * Returns a promise that resolves with the current user (or null).
 * Useful for page-load auth gates.
 */
export function waitForAuth() {
    return new Promise(resolve => {
        const unsub = onAuthStateChanged(_auth, user => {
            unsub();
            resolve(user);
        });
    });
}

export function onAuthChange(cb) {
    return onAuthStateChanged(_auth, cb);
}

export async function signInWithGoogle() {
    const provider = new GoogleAuthProvider();
    const result   = await signInWithPopup(_auth, provider);
    return result.user;
}

export function signOutUser() {
    return signOut(_auth);
}

export function currentUser() {
    return _auth?.currentUser ?? null;
}
