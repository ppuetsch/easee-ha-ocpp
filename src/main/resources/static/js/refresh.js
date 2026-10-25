(function () {
    const REFRESH_INTERVAL_MS = 5000;
    const FRAGMENT_URL = '/fragments/state';
    const container = document.getElementById('zustand');

    async function refreshFragment() {
        try {
            const response = await fetch(FRAGMENT_URL, { cache: 'no-store' });
            if (response.ok) {
                container.innerHTML = await response.text();
            }
        } catch (error) {
            // Netzwerkfehler: beim nächsten Intervall erneut versuchen, die alte Anzeige bleibt stehen.
        }
    }

    setInterval(refreshFragment, REFRESH_INTERVAL_MS);
})();
